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

/** A single physical body made from one block or a selected block structure. */
public final class PhysicalBlockEntity extends Entity implements PhysicalistBodyProvider, PhysicalistScalable {
    public static final int MAX_BLOCKS = 128;
    public static final int MAX_SHAPE_BOXES = 512;
    public static final int MAX_SPAN = 16;
    public record BlockPart(BlockState state, BlockPos offset) {}
    private record SourcePart(BlockPos position, BlockState state) {}

    private static final EntityDataAccessor<BlockState> STATE = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROLL = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<CompoundTag> STRUCTURE = SynchedEntityData.defineId(
            PhysicalBlockEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private final List<BlockPart> parts = new ArrayList<>();
    private final List<AABB> localShape = new ArrayList<>();
    private final BlockBody body = new BlockBody();
    private Vec3 localCenter = new Vec3(.5, .5, .5);
    private float modelScale = 1;
    private float previousRoll;
    private double collisionRadius = .9;
    private Vec3 clientTarget;
    private float clientTargetYaw, clientTargetPitch;
    private int clientLerpSteps;

    public PhysicalBlockEntity(EntityType<? extends PhysicalBlockEntity> type, Level level) {
        super(type, level);
    }

    /** Capture a block's exact voxel collision while it is still in the world. */
    public static PhysicalBlockEntity fromBlock(Level level, BlockPos pos, BlockState state) {
        return build(level, List.of(new SourcePart(pos, state)));
    }

    /** Capture a bounded group of blocks as one rigid body. Does not remove the world blocks. */
    public static PhysicalBlockEntity fromBlocks(Level level, List<BlockPos> positions) {
        if (positions.isEmpty() || positions.size() > MAX_BLOCKS) return null;
        List<SourcePart> source = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) source.add(new SourcePart(pos, level.getBlockState(pos)));
        return build(level, source);
    }

    private static PhysicalBlockEntity build(Level level, List<SourcePart> source) {
        if (source.isEmpty() || source.size() > MAX_BLOCKS) return null;
        int minX = source.stream().mapToInt(p -> p.position().getX()).min().orElseThrow();
        int minY = source.stream().mapToInt(p -> p.position().getY()).min().orElseThrow();
        int minZ = source.stream().mapToInt(p -> p.position().getZ()).min().orElseThrow();
        int maxX = source.stream().mapToInt(p -> p.position().getX()).max().orElseThrow();
        int maxY = source.stream().mapToInt(p -> p.position().getY()).max().orElseThrow();
        int maxZ = source.stream().mapToInt(p -> p.position().getZ()).max().orElseThrow();
        if (maxX - minX >= MAX_SPAN || maxY - minY >= MAX_SPAN || maxZ - minZ >= MAX_SPAN) return null;
        PhysicalBlockEntity entity = PhysicalistContent.PHYSICAL_BLOCK.get().create(level);
        if (entity == null) return null;
        for (SourcePart block : source) {
            BlockState state = block.state();
            if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return null;
            VoxelShape shape = state.getCollisionShape(level, block.position());
            if (shape.isEmpty()) shape = state.getShape(level, block.position());
            List<AABB> boxes = shape.toAabbs();
            if (boxes.isEmpty() || entity.localShape.size() + boxes.size() > MAX_SHAPE_BOXES) return null;
            BlockPos offset = block.position().subtract(new BlockPos(minX, minY, minZ));
            entity.parts.add(new BlockPart(state, offset));
            for (AABB box : boxes) entity.localShape.add(box.move(offset.getX(), offset.getY(), offset.getZ()));
        }
        entity.localCenter = new Vec3((maxX - minX + 1) * .5, (maxY - minY + 1) * .5,
                (maxZ - minZ + 1) * .5);
        entity.updateCollisionRadius();
        entity.entityData.set(STATE, entity.parts.get(0).state());
        entity.entityData.set(STRUCTURE, entity.writeStructure());
        entity.setPos(minX + entity.localCenter.x, minY + entity.localCenter.y,
                minZ + entity.localCenter.z);
        entity.refreshDimensions();
        return entity;
    }

    public List<BlockPart> parts() { return List.copyOf(parts); }
    public Vec3 localCenter() { return localCenter; }
    public BlockState blockState() { return entityData.get(STATE); }
    public float roll() { return entityData.get(ROLL); }
    public float visualRoll(float partialTick) {
        return net.minecraft.util.Mth.rotLerp(partialTick, previousRoll, roll());
    }
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
        builder.define(STRUCTURE, new CompoundTag());
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (SCALE.equals(key)) modelScale = entityData.get(SCALE);
        if (STRUCTURE.equals(key)) readStructure(entityData.get(STRUCTURE));
        if (SCALE.equals(key) || STRUCTURE.equals(key)) refreshDimensions();
    }

    @Override public EntityDimensions getDimensions(Pose pose) {
        float diameter = (float) Math.max(.2, collisionRadius * modelScale * 2);
        return EntityDimensions.scalable(diameter, diameter);
    }

    @Override protected AABB makeBoundingBox() {
        double radius = Math.max(.1, collisionRadius * modelScale);
        return new AABB(getX() - radius, getY() - radius, getZ() - radius,
                getX() + radius, getY() + radius, getZ() + radius);
    }

    @Override public void tick() {
        previousRoll = roll();
        super.tick();
        if (level().isClientSide) {
            if (clientLerpSteps > 0 && clientTarget != null) {
                double fraction = 1.0 / clientLerpSteps--;
                setPos(position().lerp(clientTarget, fraction));
                setYRot(net.minecraft.util.Mth.rotLerp((float) fraction, getYRot(), clientTargetYaw));
                setXRot(net.minecraft.util.Mth.rotLerp((float) fraction, getXRot(), clientTargetPitch));
            }
        } else if (!localShape.isEmpty()) {
            PhysicalistSimulation.step(body);
            collideWithEntities();
        }
    }

    private void collideWithEntities() {
        List<CompoundCollision.Box> own = body.collisionBoxes();
        int checked = 0;
        for (Entity other : level().getEntities(this, getBoundingBox().inflate(.1),
                candidate -> candidate.isAlive() && candidate.isPushable()
                        && !(candidate instanceof PhysicalBlockEntity block && block.getId() < getId()))) {
            if (++checked > 24) break;
            List<CompoundCollision.Box> theirs = other instanceof PhysicalBlockEntity block
                    ? block.physicalistBody().collisionBoxes()
                    : List.of(CompoundCollision.box(other.getBoundingBox()));
            boolean resolved = false;
            for (CompoundCollision.Box a : own) {
                if (resolved) break;
                for (CompoundCollision.Box b : theirs) {
                    CompoundCollision.Contact contact = CompoundCollision.contact(a, b);
                    if (contact == null) continue;
                    Vec3 normal = contact.normal();
                    double depth = Math.min(.25, contact.depth());
                    if (other instanceof PhysicalBlockEntity block) {
                        setPos(position().add(normal.scale(depth * .5)));
                        block.setPos(block.position().subtract(normal.scale(depth * .5)));
                    } else {
                        setPos(position().add(normal.scale(depth)));
                    }
                    Vec3 relative = getDeltaMovement().subtract(other.getDeltaMovement());
                    double closing = Math.max(0, -relative.dot(normal));
                    if (closing > 0) {
                        double mass = body.aerodynamicMass();
                        double otherMass = other instanceof PhysicalBlockEntity block
                                ? block.physicalistBody().aerodynamicMass() : 1;
                        Vec3 impulse = normal.scale(closing / (1 / mass + 1 / otherMass));
                        setDeltaMovement(getDeltaMovement().add(impulse.scale(1 / mass)));
                        other.setDeltaMovement(other.getDeltaMovement().subtract(impulse.scale(1 / otherMass)));
                        Vec3 arm = contact.point().subtract(position());
                        double inertia = Math.max(.5, mass * collisionRadius * collisionRadius);
                        body.angular = body.angular.add(arm.cross(impulse).scale(1 / inertia));
                        hasImpulse = true;
                        other.hasImpulse = true;
                    }
                    resolved = true;
                    break;
                }
            }
        }
    }

    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        if (!level().isClientSide) {
            super.lerpTo(x, y, z, yaw, pitch, steps);
            return;
        }
        clientTarget = new Vec3(x, y, z);
        clientTargetYaw = yaw;
        clientTargetPitch = pitch;
        clientLerpSteps = Math.clamp(steps, 1, 3);
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
                Vec3 offset = box.getCenter().subtract(localCenter).scale(scale);
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
        @Override public double aerodynamicMass() {
            return Math.max(.1, parts.size() * Math.pow(physicalistScale(), 3));
        }
        @Override public void rotate(Vec3 value, double fraction) {
            setXRot(getXRot() + (float) Math.toDegrees(value.x * fraction));
            setYRot(getYRot() - (float) Math.toDegrees(value.y * fraction));
            entityData.set(ROLL, roll() + (float) Math.toDegrees(value.z * fraction));
        }
    }

    private void updateCollisionRadius() {
        double radius = .1;
        for (AABB box : localShape) {
            double x = Math.max(Math.abs(box.minX - localCenter.x), Math.abs(box.maxX - localCenter.x));
            double y = Math.max(Math.abs(box.minY - localCenter.y), Math.abs(box.maxY - localCenter.y));
            double z = Math.max(Math.abs(box.minZ - localCenter.z), Math.abs(box.maxZ - localCenter.z));
            radius = Math.max(radius, Math.sqrt(x * x + y * y + z * z));
        }
        collisionRadius = radius + .05;
    }

    private CompoundTag writeStructure() {
        CompoundTag structure = new CompoundTag();
        structure.putDouble("CenterX", localCenter.x);
        structure.putDouble("CenterY", localCenter.y);
        structure.putDouble("CenterZ", localCenter.z);
        ListTag blocks = new ListTag();
        for (BlockPart part : parts) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("X", part.offset().getX());
            entry.putInt("Y", part.offset().getY());
            entry.putInt("Z", part.offset().getZ());
            entry.put("BlockState", NbtUtils.writeBlockState(part.state()));
            blocks.add(entry);
        }
        structure.put("Blocks", blocks);
        ListTag boxes = new ListTag();
        for (AABB box : localShape) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("x0", box.minX); entry.putDouble("y0", box.minY); entry.putDouble("z0", box.minZ);
            entry.putDouble("x1", box.maxX); entry.putDouble("y1", box.maxY); entry.putDouble("z1", box.maxZ);
            boxes.add(entry);
        }
        structure.put("CollisionBoxes", boxes);
        return structure;
    }

    private void readStructure(CompoundTag structure) {
        parts.clear();
        localShape.clear();
        if (structure.isEmpty()) return;
        HolderGetter<net.minecraft.world.level.block.Block> blocks = level().holderLookup(Registries.BLOCK);
        ListTag blockList = structure.getList("Blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(blockList.size(), MAX_BLOCKS); i++) {
            CompoundTag entry = blockList.getCompound(i);
            if (!entry.contains("BlockState", Tag.TAG_COMPOUND)) continue;
            BlockPos offset = new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
            if (offset.getX() < 0 || offset.getX() >= MAX_SPAN || offset.getY() < 0
                    || offset.getY() >= MAX_SPAN || offset.getZ() < 0 || offset.getZ() >= MAX_SPAN) continue;
            parts.add(new BlockPart(NbtUtils.readBlockState(blocks, entry.getCompound("BlockState")), offset));
        }
        localCenter = new Vec3(structure.getDouble("CenterX"), structure.getDouble("CenterY"),
                structure.getDouble("CenterZ"));
        ListTag boxes = structure.getList("CollisionBoxes", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(boxes.size(), MAX_SHAPE_BOXES); i++) {
            CompoundTag entry = boxes.getCompound(i);
            AABB box = new AABB(entry.getDouble("x0"), entry.getDouble("y0"), entry.getDouble("z0"),
                    entry.getDouble("x1"), entry.getDouble("y1"), entry.getDouble("z1"));
            if (box.getXsize() > 0 && box.getYsize() > 0 && box.getZsize() > 0) localShape.add(box);
        }
        updateCollisionRadius();
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Structure", writeStructure());
        tag.putFloat("PhysicalScale", (float) physicalistScale());
        tag.putFloat("PhysicalRoll", roll());
        tag.putDouble("AngularX", body.angular.x);
        tag.putDouble("AngularY", body.angular.y);
        tag.putDouble("AngularZ", body.angular.z);
    }

    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Structure", Tag.TAG_COMPOUND)) {
            readStructure(tag.getCompound("Structure"));
        } else if (tag.contains("BlockState", Tag.TAG_COMPOUND)) {
            // Migrate the single-block entities saved by 0.3.0.
            CompoundTag old = new CompoundTag();
            old.putDouble("CenterX", .5); old.putDouble("CenterY", .5); old.putDouble("CenterZ", .5);
            CompoundTag block = new CompoundTag();
            block.putInt("X", 0); block.putInt("Y", 0); block.putInt("Z", 0);
            block.put("BlockState", tag.getCompound("BlockState"));
            ListTag list = new ListTag(); list.add(block);
            old.put("Blocks", list);
            old.put("CollisionBoxes", tag.getList("CollisionBoxes", Tag.TAG_COMPOUND));
            readStructure(old);
        }
        if (!parts.isEmpty()) entityData.set(STATE, parts.get(0).state());
        entityData.set(STRUCTURE, writeStructure());
        setPhysicalistScale(Math.clamp(tag.getFloat("PhysicalScale"), .1f, 16f));
        entityData.set(ROLL, tag.getFloat("PhysicalRoll"));
        body.angular = new Vec3(tag.getDouble("AngularX"), tag.getDouble("AngularY"), tag.getDouble("AngularZ"));
    }
}
