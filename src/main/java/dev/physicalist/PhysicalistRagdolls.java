package dev.physicalist;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Public ragdoll API. Other mods can turn any entity into a ragdoll or give a dying entity the push it should fly
 * off with:
 * <pre>
 * PhysicalistRagdolls.spawn(zombie, shotDirection.scale(.6));         // limp body thrown by a shot
 * PhysicalistRagdolls.deathPush(target, bulletVelocity.scale(.02));   // before killing it, with ragdolls.onDeath on
 * </pre>
 */
public final class PhysicalistRagdolls {
    private static final String DEATH_PUSH = "PhysicalistDeathPush";
    /** Ragdolls near one point; more are not made on death (commands have their own limit). */
    public static final int MAXIMUM_NEARBY = 64;
    private static final List<LivingEntity> HIDDEN = new ArrayList<>();

    /**
     * A ragdoll copy of an entity where it stands, moving with its velocity plus push (blocks per tick). A mob is
     * replaced by its ragdoll; a player keeps their own entity and gets a copy with their skin.
     *
     * @return the ragdoll, or null if none could be spawned
     */
    public static PlayerRagdollEntity spawn(Entity source, Vec3 push) {
        if (!(source.level() instanceof ServerLevel level) || source instanceof PlayerRagdollEntity
                || source instanceof ArmorStand || PhysicalistBodies.find(source) != null) return null;
        var doll = PhysicalistContent.PLAYER_RAGDOLL.get().create(level);
        if (doll == null) return null;
        doll.initialize(source);
        if (push != null && RigidBodyState.finite(push) && push.lengthSqr() > 0)
            doll.setDeltaMovement(push.length() > 3 ? push.normalize().scale(3) : push);
        if (!level.addFreshEntity(doll)) return null;
        if (!(source instanceof net.minecraft.world.entity.player.Player)) source.discard();
        return doll;
    }

    /** Push (blocks per tick) the entity's death ragdoll flies off with, on top of its own velocity. */
    public static void deathPush(LivingEntity entity, Vec3 push) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putDouble("X", push.x); tag.putDouble("Y", push.y); tag.putDouble("Z", push.z);
        entity.getPersistentData().put(DEATH_PUSH, tag);
    }

    public static boolean onDeathEnabled() { return PhysicalistConfig.RAGDOLL_ON_DEATH.get(); }
    public static void setOnDeath(boolean enabled) { PhysicalistConfig.RAGDOLL_ON_DEATH.set(enabled); }

    static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (event.isCanceled() || !onDeathEnabled() || !(entity.level() instanceof ServerLevel level)
                || entity instanceof ArmorStand || entity.getBbWidth() > 4 || entity.getBbHeight() > 6) return;
        if (entity instanceof ServerPlayer && !PhysicalistConfig.RAGDOLL_ON_DEATH_PLAYERS.get()) return;
        if (level.getEntitiesOfClass(PlayerRagdollEntity.class, new AABB(entity.blockPosition()).inflate(128)).size()
                >= MAXIMUM_NEARBY) return;
        var doll = PhysicalistContent.PLAYER_RAGDOLL.get().create(level);
        if (doll == null) return;
        doll.initialize(entity);
        doll.setDeltaMovement(push(entity, event.getSource()));
        if (!level.addFreshEntity(doll)) return;
        // The dying entity still drops its loot and experience; it is only no longer drawn.
        entity.setInvisible(true);
        HIDDEN.add(entity);
    }

    private static Vec3 push(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
        var data = entity.getPersistentData();
        if (data.contains(DEATH_PUSH)) {
            var t = data.getCompound(DEATH_PUSH);
            Vec3 v = new Vec3(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"));
            if (RigidBodyState.finite(v)) return v.length() > 3 ? v.normalize().scale(3) : v;
        }
        // The killing blow's knockback is already in the entity's velocity; projectiles add their own flight.
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != source.getEntity() && direct.getDeltaMovement().lengthSqr() > 1e-6)
            return direct.getDeltaMovement().normalize().scale(.25);
        Vec3 from = source.getSourcePosition();
        if (from == null) return Vec3.ZERO;
        Vec3 away = entity.position().subtract(from);
        away = new Vec3(away.x, 0, away.z);
        return away.lengthSqr() < 1e-6 ? Vec3.ZERO : away.normalize().scale(.12).add(0, .05, 0);
    }

    /** Dead entities stay hidden until vanilla removes them; a respawned player is a new entity and is visible. */
    static void tick(ServerTickEvent.Post event) {
        HIDDEN.removeIf(e -> {
            if (e.isRemoved() || !e.isDeadOrDying()) return true;
            e.setInvisible(true);
            return false;
        });
    }

    private PhysicalistRagdolls() {}
}
