package dev.physicalist;

import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A free physical entity built from one block and that block's voxel collision shape. */
public final class PhysicalBlockEntity extends Entity implements PhysicalistBodyProvider, PhysicalistScalable {
    public static final int MAX_SHAPE_BOXES = 64;
    private static final EntityDataAccessor<BlockState> STATE = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.FLOAT);
    private final List<AABB> localShape = new ArrayList<>();
    private final BlockBody body = new BlockBody();
    private float modelScale = 1;

    public PhysicalBlockEntity(EntityType<? extends PhysicalBlockEntity> type, Level level) {
        super(type, level);
    }

    /** Capture the exact block collision while it is still in the world. */
    public static PhysicalBlockEntity fromBlock(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.hasBlockEntity()) return null;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) shape = state.getShape(level, pos);
        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty() || boxes.size() > MAX_SHAPE_BOXES) return null;
        PhysicalBlockEntity entity = PhysicalistContent.PHYSICAL_BLOCK.get().create(level);
        if (entity == null) return null;
        entity.entityData.set(STATE, state);
        entity.localShape.addAll(boxes);
        entity.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        return entity;
    }

    public BlockState blockState() { return entityData.get(STATE); }
    public float roll() { return entityData.get(ROLL); }
    @Override public double physicalistScale() { return entityData.get(SCALE); }
    @Override public void setPhysicalistScale(double scale) {
        if (!Double.isFinite(scale) || scale < .1 || scale > 16)
            throw new IllegalArgumentException("Scale must be between 0.1 and 16");
        modelScale = (float) scale;
        entityData.set(SCALE, modelScale);
        refreshDimensions();
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(STATE, Blocks.STONE.defaultBlockState());
        builder.define(SCALE, 1f);
        builder.define(ROLL, 0f);
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (SCALE.equals(key)) {
            modelScale = entityData.get(SCALE);
            refreshDimensions();
        }
    }

    @Override public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(Math.max(.2f, modelScale * 1.8f),
                Math.max(.2f, modelScale * 1.8f));
    }

    @Override protected AABB makeBoundingBox() {
        double radius = Math.max(.1, modelScale * .9);
        return new AABB(getX() - radius, getY() - radius, getZ() - radius,
                getX() + radius, getY() + radius, getZ() + radius);
    }

    @Override public void tick() {
        super.tick();
        if (!level().isClientSide && !localShape.isEmpty()) PhysicalistSimulation.step(body);
    }

    @Override public boolean isPickable() { return true; }
    @Override public boolean isPushable() { return true; }
    @Override public PhysicsBody physicalistBody() { return body; }

    private Quaternionf orientation() {
        return Axis.YP.rotationDegrees(-getYRot())
                .mul(Axis.XP.rotationDegrees(getXRot()))
                .mul(Axis.ZP.rotationDegrees(roll()));
    }

    private static Vec3 rotate(Quaternionf q, Vec3 vector) {
        Vector3f transformed = q.transform(new Vector3f((float) vector.x, (float) vector.y, (float) vector.z));
        return new Vec3(transformed.x(), transformed.y(), transformed.z());
    }

    private final class BlockBody implements PhysicsBody {
        private Vec3 angular = Vec3.ZERO;
        @Override public Entity entity() { return PhysicalBlockEntity.this; }
        @Override public List<CompoundCollision.Box> collisionBoxes() {
            Quaternionf rotation = orientation();
            Vec3[] axes = {PhysicalBlockEntity.rotate(rotation, new Vec3(1, 0, 0)),
                    PhysicalBlockEntity.rotate(rotation, new Vec3(0, 1, 0)),
                    PhysicalBlockEntity.rotate(rotation, new Vec3(0, 0, 1))};
            double scale = physicalistScale();
            List<CompoundCollision.Box> result = new ArrayList<>(localShape.size());
            for (AABB box : localShape) {
                Vec3 offset = box.getCenter().subtract(.5, .5, .5).scale(scale);
                result.add(new CompoundCollision.Box(position().add(PhysicalBlockEntity.rotate(rotation, offset)), axes,
                        new double[]{box.getXsize() * scale / 2, box.getYsize() * scale / 2,
                                box.getZsize() * scale / 2}));
            }
            return result;
        }
        @Override public Vec3 forward() { return PhysicalBlockEntity.rotate(orientation(), new Vec3(0, 0, 1)); }
        @Override public Vec3 up() { return PhysicalBlockEntity.rotate(orientation(), new Vec3(0, 1, 0)); }
        @Override public Vec3 angularVelocity() { return angular; }
        @Override public void setAngularVelocity(Vec3 value) { angular = value; }
        @Override public double aerodynamicMass() { return Math.max(.1, physicalistScale() * physicalistScale() * physicalistScale()); }
        @Override public void rotate(Vec3 value, double fraction) {
            setXRot(getXRot() + (float) Math.toDegrees(value.x * fraction));
            setYRot(getYRot() - (float) Math.toDegrees(value.y * fraction));
            entityData.set(ROLL, roll() + (float) Math.toDegrees(value.z * fraction));
        }
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("BlockState", NbtUtils.writeBlockState(blockState()));
        tag.putFloat("PhysicalScale", (float) physicalistScale());
        tag.putFloat("PhysicalRoll", roll());
        tag.putDouble("AngularX", body.angular.x);
        tag.putDouble("AngularY", body.angular.y);
        tag.putDouble("AngularZ", body.angular.z);
        ListTag boxes = new ListTag();
        for (AABB box : localShape) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("x0", box.minX); entry.putDouble("y0", box.minY); entry.putDouble("z0", box.minZ);
            entry.putDouble("x1", box.maxX); entry.putDouble("y1", box.maxY); entry.putDouble("z1", box.maxZ);
            boxes.add(entry);
        }
        tag.put("CollisionBoxes", boxes);
    }

    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        HolderGetter<net.minecraft.world.level.block.Block> blocks = level().holderLookup(Registries.BLOCK);
        if (tag.contains("BlockState", Tag.TAG_COMPOUND))
            entityData.set(STATE, NbtUtils.readBlockState(blocks, tag.getCompound("BlockState")));
        setPhysicalistScale(Math.clamp(tag.getFloat("PhysicalScale"), .1f, 16f));
        entityData.set(ROLL, tag.getFloat("PhysicalRoll"));
        body.angular = new Vec3(tag.getDouble("AngularX"), tag.getDouble("AngularY"), tag.getDouble("AngularZ"));
        localShape.clear();
        ListTag boxes = tag.getList("CollisionBoxes", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(boxes.size(), MAX_SHAPE_BOXES); i++) {
            CompoundTag entry = boxes.getCompound(i);
            AABB box = new AABB(entry.getDouble("x0"), entry.getDouble("y0"), entry.getDouble("z0"),
                    entry.getDouble("x1"), entry.getDouble("y1"), entry.getDouble("z1"));
            if (box.getXsize() > 0 && box.getYsize() > 0 && box.getZsize() > 0) localShape.add(box);
        }
    }
}
