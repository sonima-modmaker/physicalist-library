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
    private float clientVisualRoll;
    private boolean clientRollReady;
    private double collisionRadius = .9;
    private Vec3 clientTarget;
    private float clientTargetYaw, clientTargetPitch;
    private int clientLerpSteps;
    private int quietTicks;
    private boolean sleeping;

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
        return net.minecraft.util.Mth.rotLerp(partialTick, previousRoll,
                level().isClientSide ? clientVisualRoll : roll());
    }
    @Override public double physicalistScale() { return entityData.get(SCALE); }
    @Override public void setPhysicalistScale(double scale) {
        if (!Double.isFinite(scale) || scale < .1 || scale > 16)
            throw new IllegalArgumentException("Scale must be between 0.1 and 16");
        modelScale = (float) scale;
        entityData.set(SCALE, modelScale);
        body.invalidateCollisionCache();
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
        if (level().isClientSide) {
            if (!clientRollReady) {
                clientVisualRoll = roll();
                clientRollReady = true;
            }
            previousRoll = clientVisualRoll;
            clientVisualRoll = net.minecraft.util.Mth.rotLerp(.55f, clientVisualRoll, roll());
        } else previousRoll = roll();
        super.tick();
        if (level().isClientSide) {
            if (clientLerpSteps > 0 && clientTarget != null) {
                double fraction = 1.0 / clientLerpSteps--;
                setPos(position().lerp(clientTarget, fraction));
                setYRot(net.minecraft.util.Mth.rotLerp((float) fraction, getYRot(), clientTargetYaw));
                setXRot(net.minecraft.util.Mth.rotLerp((float) fraction, getXRot(), clientTargetPitch));
            }
        } else if (!localShape.isEmpty()) {
            boolean still = getDeltaMovement().lengthSqr() < 1e-5 && body.angular.lengthSqr() < 1e-5;
            if (sleeping && still && (tickCount % 8 != 0 || supported())
                    && (tickCount % 5 != 0 || !movingExternalContact())) {
                collideWithEntities();
                return;
            }
            sleeping = false;
            PhysicalistSimulation.step(body);
            collideWithEntities();
            boolean restingOnBlock = getDeltaMovement().lengthSqr() < .0144
                    && body.angular.lengthSqr() < .0144 && supported();
            if (restingOnBlock) {
                // Contact impulses from gravity can feed a tiny roll forever. Dissipate
                // that energy only while the assembly is supported and already slow.
                Vec3 motion = getDeltaMovement();
                setDeltaMovement(new Vec3(motion.x * .62, Math.abs(motion.y) < .08 ? 0 : motion.y,
                        motion.z * .62));
                body.angular = body.angular.scale(.55);
            }
            if (restingOnBlock && getDeltaMovement().lengthSqr() < .0009
                    && body.angular.lengthSqr() < .0004) {
                if (++quietTicks >= 6) {
                    setDeltaMovement(Vec3.ZERO);
                    body.angular = Vec3.ZERO;
                    sleeping = true;
                }
            } else quietTicks = 0;
        }
    }

    private boolean supported() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel)) return false;
        var boxes = body.collisionBoxes();
        double lowest = Double.POSITIVE_INFINITY;
        for (CompoundCollision.Box box : boxes) {
            Vec3[] a = box.axes(); double[] h = box.half();
            lowest = Math.min(lowest, box.center().y
                    - Math.abs(a[0].y * h[0]) - Math.abs(a[1].y * h[1]) - Math.abs(a[2].y * h[2]));
        }
        List<CompoundCollision.Box> underside = new ArrayList<>();
        int checked = 0;
        for (CompoundCollision.Box box : boxes) {
            Vec3[] axes = box.axes();
            double[] h = box.half();
            double rx = Math.abs(axes[0].x * h[0]) + Math.abs(axes[1].x * h[1]) + Math.abs(axes[2].x * h[2]);
            double ry = Math.abs(axes[0].y * h[0]) + Math.abs(axes[1].y * h[1]) + Math.abs(axes[2].y * h[2]);
            double rz = Math.abs(axes[0].z * h[0]) + Math.abs(axes[1].z * h[1]) + Math.abs(axes[2].z * h[2]);
            if (box.center().y - ry > lowest + .15) continue;
            Vec3 low = box.center().add(0, -.075, 0);
            var lowered = new CompoundCollision.Box(low, axes, h);
            underside.add(lowered);
            AABB query = new AABB(low.x - rx, low.y - ry, low.z - rz,
                    low.x + rx, low.y + ry, low.z + rz).inflate(.02);
            for (VoxelShape shape : level().getBlockCollisions(this, query)) {
                for (AABB block : shape.toAabbs()) {
                    var contact = CompoundCollision.contact(lowered, CompoundCollision.box(block));
                    if (contact != null && contact.normal().y > .55) return true;
                }
            }
            if (++checked >= 24) break;
        }
        if (PhysicalistExternalCollisions.available() && !underside.isEmpty()) {
            for (var collider : PhysicalistExternalCollisions.gather(
                    (net.minecraft.server.level.ServerLevel) level(), this, getBoundingBox().inflate(.2))) {
                if (collider.velocity().lengthSqr() > 1e-5) continue;
                for (var lowered : underside) {
                    var contact = CompoundCollision.contact(lowered, collider.box());
                    if (contact != null && contact.normal().y > .55) return true;
                }
            }
        }
        if (!underside.isEmpty()) {
            AABB search = getBoundingBox().move(0, -.1, 0).inflate(.05);
            for (Entity candidate : level().getEntities(this, search,
                    entity -> entity.isAlive() && entity instanceof PhysicalistBodyProvider)) {
                if (candidate.getDeltaMovement().lengthSqr() > .0004) continue;
                PhysicsBody support = ((PhysicalistBodyProvider) candidate).physicalistBody();
                if (support == null) continue;
                for (CompoundCollision.Box lower : support.collisionBoxes()) {
                    if (!CompoundCollision.bounds(lower).intersects(search)) continue;
                    for (CompoundCollision.Box raised : underside) {
                        var contact = CompoundCollision.contact(raised, lower);
                        if (contact != null && contact.normal().y > .55) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean movingExternalContact() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)
                || !PhysicalistExternalCollisions.available()) return false;
        var boxes = body.collisionBoxes();
        for (var collider : PhysicalistExternalCollisions.gather(level, this, getBoundingBox().inflate(.3))) {
            if (collider.velocity().lengthSqr() < 1e-5) continue;
            for (var box : boxes)
                if (CompoundCollision.contact(box, collider.box()) != null) return true;
        }
        return false;
    }

    private void collideWithEntities() {
        List<CompoundCollision.Box> own = body.collisionBoxes();
        if (own.isEmpty()) return;
        List<AABB> partBounds = new ArrayList<>(own.size());
        AABB tightBounds = null;
        for (CompoundCollision.Box part : own) {
            AABB bounds = CompoundCollision.bounds(part);
            partBounds.add(bounds);
            tightBounds = tightBounds == null ? bounds : tightBounds.minmax(bounds);
        }
        AABB search = tightBounds.inflate(.1);
        int checked = 0;
        for (Entity other : level().getEntities(this, search,
                candidate -> candidate.isAlive() && candidate.isPushable()
                        && !(candidate instanceof PhysicalBlockEntity block && block.getId() < getId()))) {
            if (++checked > 24) break;
            if (!search.intersects(other.getBoundingBox())) continue;
            List<CompoundCollision.Box> theirs = other instanceof PhysicalBlockEntity block
                    ? block.physicalistBody().collisionBoxes()
                    : List.of(CompoundCollision.box(other.getBoundingBox()));
            List<AABB> theirBounds = new ArrayList<>(theirs.size());
            for (CompoundCollision.Box part : theirs) theirBounds.add(CompoundCollision.bounds(part));
            CompoundCollision.Contact best = null;
            for (int i = 0; i < own.size(); i++) {
                if (!partBounds.get(i).intersects(other.getBoundingBox())) continue;
                CompoundCollision.Box a = own.get(i);
                for (int j = 0; j < theirs.size(); j++) {
                    if (!partBounds.get(i).intersects(theirBounds.get(j))) continue;
                    CompoundCollision.Box b = theirs.get(j);
                    CompoundCollision.Contact contact = CompoundCollision.contact(a, b);
                    if (contact == null
                            || other instanceof net.minecraft.world.entity.player.Player
                                    && contact.normal().y > .5) continue;
                    if (best == null || contact.depth() > best.depth()) best = contact;
                }
            }
            if (best == null) continue;
            Vec3 normal = best.normal();
            Vec3 relative = getDeltaMovement().subtract(other.getDeltaMovement());
            double closing = Math.max(0, -relative.dot(normal));
            double correction = Math.min(.08, Math.max(0, best.depth() - .012) * .35);
            if (correction < .002 && closing < .025) continue;
            sleeping = false;
            quietTicks = 0;
            if (other instanceof PhysicalBlockEntity block) {
                block.sleeping = false;
                block.quietTicks = 0;
            }
            if (correction > 0) {
                if (other instanceof PhysicalBlockEntity block) {
                    double mass = Math.max(.1, body.aerodynamicMass());
                    double otherMass = Math.max(.1, block.physicalistBody().aerodynamicMass());
                    double share = otherMass / (mass + otherMass);
                    setPos(position().add(normal.scale(correction * share)));
                    block.setPos(block.position().subtract(normal.scale(correction * (1 - share))));
                } else {
                    setPos(position().add(normal.scale(correction)));
                }
            }
            if (closing > .015) {
                double mass = body.aerodynamicMass();
                double otherMass = other instanceof PhysicalBlockEntity block
                        ? block.physicalistBody().aerodynamicMass() : 1;
                Vec3 impulse = normal.scale(closing / (1 / mass + 1 / otherMass));
                setDeltaMovement(getDeltaMovement().add(impulse.scale(1 / mass)));
                other.setDeltaMovement(other.getDeltaMovement().subtract(impulse.scale(1 / otherMass)));
                Vec3 arm = best.point().subtract(position());
                double inertia = Math.max(.5, mass * collisionRadius * collisionRadius);
                if (closing > .08)
                    body.angular = body.angular.add(arm.cross(impulse).scale(1 / inertia));
                hasImpulse = true;
                other.hasImpulse = true;
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
        // Keep a short interpolation buffer when a packet is delayed. One-step
        // snapping made assemblies visibly twitch on otherwise smooth flight.
        clientLerpSteps = position().distanceToSqr(clientTarget) > 256 ? 1 : 2;
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
        private Vec3 cachedPosition;
        private float cachedYaw, cachedPitch, cachedRoll;
        private double cachedScale;
        private List<CompoundCollision.Box> cachedBoxes;

        private void invalidateCollisionCache() { cachedBoxes = null; }
        @Override public Entity entity() { return PhysicalBlockEntity.this; }
        @Override public List<CompoundCollision.Box> collisionBoxes() {
            Vec3 position = position();
            float yaw = getYRot(), pitch = getXRot(), currentRoll = roll();
            double scale = physicalistScale();
            if (cachedBoxes != null && position.equals(cachedPosition)
                    && yaw == cachedYaw && pitch == cachedPitch && currentRoll == cachedRoll
                    && scale == cachedScale) return cachedBoxes;
            Quaternionf rotation = orientation();
            Vec3[] axes = {PhysicalBlockEntity.rotate(rotation, new Vec3(1, 0, 0)),
                    PhysicalBlockEntity.rotate(rotation, new Vec3(0, 1, 0)),
                    PhysicalBlockEntity.rotate(rotation, new Vec3(0, 0, 1))};
            List<CompoundCollision.Box> result = new ArrayList<>(localShape.size());
            for (AABB box : localShape) {
                Vec3 offset = box.getCenter().subtract(localCenter).scale(scale);
                result.add(new CompoundCollision.Box(position.add(PhysicalBlockEntity.rotate(rotation, offset)), axes,
                        new double[]{box.getXsize() * scale / 2, box.getYsize() * scale / 2,
                                box.getZsize() * scale / 2}));
            }
            cachedPosition = position;
            cachedYaw = yaw;
            cachedPitch = pitch;
            cachedRoll = currentRoll;
            cachedScale = scale;
            return cachedBoxes = List.copyOf(result);
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
        body.invalidateCollisionCache();
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
