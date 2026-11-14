package dev.physicalist.soft;

import dev.physicalist.PhysicalistContent;
import dev.physicalist.nativecore.NativeSoft;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A soft body built from a model: its nodes and beams run in the native solver on the dimension's soft-body thread,
 * every viewer receives the model once and then time-stamped node positions, and draws the mesh pinned to them.
 * A model with a seat can be flown or driven: the pilot's controls deflect its control surfaces and run its engine.
 */
public final class SoftBodyEntity extends Entity {
    /** Player UUID -> the soft body that player spawned last, for R. */
    static final Map<UUID, UUID> LAST = new HashMap<>();
    private static final double FRICTION = 0.9, SKIN_DRAG = 0.005, ROLLING = 0.03, BRAKING = 0.7;
    /** Largest deflection per channel (radians): elevator, ailerons, rudder. */
    private static final double[] THROW = {0.35, 0.3, 0.3, 0.4};

    private byte[] blob = new byte[0];
    private double userScale = 1;
    private float yaw;
    private Vec3 origin = Vec3.ZERO, launch = Vec3.ZERO;
    private SoftStructure structure;
    private boolean broken, fresh;
    // Server physics state.
    private SoftWorlds.Handle handle;
    SoftWorlds.Snapshot applied;
    private int sentVersion = -1, quietTicks;
    private long previousTick;
    private boolean[] open = new boolean[0], fallen = new boolean[0];
    /** Ticks a door has been closing, -1 when it is not. */
    private int[] closing = new int[0];
    /** Open doors that reached the end and are held there by their check strap. */
    private boolean[] held = new boolean[0];
    /** Where each door's hinge beams start in the watched-beam list. */
    private int[] watchStart = new int[0];
    // Pilot: stick and pedals (-1..1), throttle (0..1), engine, brakes; and what the surfaces are actually at.
    private float pitch, roll, pedals, throttle;
    private boolean engine, brake;
    private int inputAge = 1000;
    private final double[] deflection = new double[SoftStructure.CHANNELS];
    // Shared: latest node positions (world), previous ones.
    double[] nodes, previous;
    int[] components;
    int version;
    /** Latest rotor state: per rotor {angle, omega, thrust, throttle, on}. */
    float[] rotors = new float[0];
    float[] controls = new float[SoftStructure.CHANNELS];
    boolean[] beams;
    private AABB bounds;
    private double lastMove = 1;
    /** Tests: fly through the test arena's walls; keep the controls set by {@link #input} without a pilot. */
    boolean ignoreTerrain, testPilot;
    /** How much of the propeller's slipstream its surfaces feel. */
    double wash = 1;
    /** Diagnostics from the solver: pressure drag, lift, skin friction, total force (3 values each). */
    double[] probe = new double[0];
    // Client: timeline of poses and the structure once the view has built it.
    private final SoftTimeline timeline = new SoftTimeline();
    private SoftStructure shown;
    private double[] scratch = new double[0];
    private float lastHeading = Float.NaN;

    public SoftBodyEntity(EntityType<? extends SoftBodyEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        noCulling = true;
        setNoGravity(true);
    }

    public static SoftBodyEntity spawn(ServerLevel level, byte[] blob, SoftStructure structure, double userScale, Vec3 base, float yaw, Vec3 launch) {
        var e = new SoftBodyEntity(PhysicalistContent.SOFT_BODY.get(), level);
        e.blob = blob;
        e.structure = structure;
        e.userScale = userScale;
        e.yaw = yaw;
        e.origin = base;
        e.launch = launch;
        e.fresh = true;
        e.place(base);
        return e;
    }

    public SoftStructure structure() { return structure; }
    public byte[] blob() { return blob; }
    public double userScale() { return userScale; }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    /** Rest shape turned to face along yaw (model +Z forward) with its structure origin at base. */
    private void place(Vec3 base) {
        double[] r = structure.rest;
        double c = Math.cos(Math.toRadians(yaw)), s = Math.sin(Math.toRadians(yaw));
        nodes = new double[r.length];
        for (int i = 0; i < r.length; i += 3) {
            double x = r[i], z = r[i + 2];
            nodes[i] = base.x + x * c - z * s;
            nodes[i + 1] = base.y + r[i + 1];
            nodes[i + 2] = base.z + x * s + z * c;
        }
        previous = nodes.clone();
        int doors = structure.doors.size();
        open = new boolean[doors];
        fallen = new boolean[doors];
        closing = new int[doors];
        Arrays.fill(closing, -1);
        held = new boolean[doors];
        components = new int[structure.nodes];
        version = 0;
        applied = null;
        centre();
    }

    private void centre() {
        double x = 0, y = 0, z = 0, lx = 1e30, ly = 1e30, lz = 1e30, hx = -1e30, hy = -1e30, hz = -1e30;
        int n = nodes.length / 3;
        for (int i = 0; i < nodes.length; i += 3) {
            x += nodes[i]; y += nodes[i + 1]; z += nodes[i + 2];
            lx = Math.min(lx, nodes[i]); ly = Math.min(ly, nodes[i + 1]); lz = Math.min(lz, nodes[i + 2]);
            hx = Math.max(hx, nodes[i]); hy = Math.max(hy, nodes[i + 1]); hz = Math.max(hz, nodes[i + 2]);
        }
        bounds = new AABB(lx, ly, lz, hx, hy, hz).inflate(0.1);
        setPos(x / n, y / n, z / n);
    }

    @Override protected AABB makeBoundingBox() { return bounds != null ? bounds : super.makeBoundingBox(); }
    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean shouldRenderAtSqrDistance(double d) { return d < 256 * 256; }

    /** Server-thread time spent in soft-body ticks (diagnostics), and how many ticks. */
    public static long serverNanos, serverTicks;
    long ownNanos, ownTicks;

    @Override
    public void tick() {
        if (level().isClientSide) { clientTick(); return; }
        long start = System.nanoTime();
        try { serverTick(); } finally { long spent = System.nanoTime() - start; serverNanos += spent; serverTicks++; ownNanos += spent; ownTicks++; }
    }

    private void serverTick() {
        var level = (ServerLevel) level();
        if (broken) return;
        if (structure == null) {
            try { structure = SoftStructure.build(SoftModel.decode(blob), userScale); }
            catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Removing soft body with an unreadable model: {}", error.toString());
                broken = true;
                discard();
                return;
            }
            // Loaded from disk: the rest shape goes where the body was.
            Vec3 at = position();
            double[] rc = restCentre();
            double c = Math.cos(Math.toRadians(yaw)), s = Math.sin(Math.toRadians(yaw));
            place(at.subtract(rc[0] * c - rc[2] * s, rc[1], rc[0] * s + rc[2] * c));
        }
        if (!NativeSoft.AVAILABLE) return;
        if (handle == null || handle.dead()) create(level);
        if (handle == null) return;
        if (quietTicks < 40 || tickCount % 20 == 0) {
            double[] boxes = ignoreTerrain ? new double[0] : terrain(level);
            handle.op((w, b) -> NativeSoft.terrain0(w, b, boxes));
        }
        pilot();
        SoftWorlds.advance(level);
        var snap = handle.latest();
        if (snap != null && snap != applied) apply(level, snap);
    }

    private void apply(ServerLevel level, SoftWorlds.Snapshot snap) {
        int n = structure.nodes;
        if (snap.nodes().length != n * 3) return;
        long ticks = applied == null ? 1 : Math.max(1, snap.tick() - applied.tick());
        double moved = 0;
        for (int i = 0; i < n * 3; i++) moved = Math.max(moved, Math.abs(snap.nodes()[i] - nodes[i]));
        lastMove = moved / ticks;
        previous = nodes;
        previousTick = applied == null ? snap.tick() - 1 : applied.tick();
        nodes = snap.nodes();
        applied = snap;
        probe = snap.probe();
        quietTicks = snap.sleeping() ? quietTicks + 1 : 0;
        rotors = new float[snap.rotors().length / 7 * 5];
        for (int r = 0; r < rotors.length / 5; r++) {
            double[] o = snap.rotors();
            rotors[r * 5] = (float) o[r * 7]; rotors[r * 5 + 1] = (float) o[r * 7 + 1]; rotors[r * 5 + 2] = (float) o[r * 7 + 2];
            rotors[r * 5 + 3] = (float) o[r * 7 + 5]; rotors[r * 5 + 4] = (float) o[r * 7 + 6];
        }
        for (int c = 0; c < controls.length; c++) controls[c] = (float) deflection[c];
        if (snap.version() != version || components == null || snap.components() != components) {
            version = snap.version();
            components = snap.components();
            beams = snap.beams();
            for (int d = 0; d < structure.doors.size(); d++) if (!fallen[d]) {
                boolean any = false;
                int count = structure.doors.get(d).hinges().length;
                for (int k = 0; k < count; k++) any |= watchStart[d] + k < snap.watched().length && snap.watched()[watchStart[d] + k];
                fallen[d] = !any;
            }
        }
        centre();
        closeDoors();
        if (getY() < level.getMinBuildHeight() - 64) { discard(); return; }
        if (!snap.sleeping() || quietTicks < 3 || version != sentVersion) {
            PacketDistributor.sendToPlayersTrackingEntity(this, state(version != sentVersion));
            sentVersion = version;
        }
    }

    private double[] restCentre() {
        double[] r = structure.rest, c = new double[3];
        for (int i = 0; i < r.length; i++) c[i % 3] += r[i];
        for (int k = 0; k < 3; k++) c[k] /= r.length / 3.0;
        return c;
    }

    private void create(ServerLevel level) {
        if (handle != null) handle.remove();
        int n = structure.nodes;
        List<Integer> watch = new ArrayList<>();
        watchStart = new int[structure.doors.size()];
        for (int d = 0; d < structure.doors.size(); d++) {
            watchStart[d] = watch.size();
            for (int b : structure.doors.get(d).hinges()) watch.add(b);
        }
        handle = SoftWorlds.add(level, structure.nodeSpec(nodes), structure.beams, structure.aero, structure.rotors, new double[0],
                new double[]{structure.radius, FRICTION, SKIN_DRAG, SoftStructure.YIELD, SoftStructure.SHAPE_STIFFNESS, ROLLING, wash},
                watch.stream().mapToInt(Integer::intValue).toArray(), structure.beamCount());
        for (int d = 0; d < open.length; d++) {
            var door = structure.doors.get(d);
            boolean shut = !open[d], hold = open[d] && held[d];
            handle.op((w, b) -> { NativeSoft.latch0(w, b, door.latch(), shut); NativeSoft.latch0(w, b, door.hold(), hold); });
        }
        if (fresh && launch.lengthSqr() > 0) {
            double[] v = {launch.x, launch.y, launch.z};
            handle.op((w, b) -> NativeSoft.velocity0(w, b, v));
        }
        fresh = false;
        quietTicks = 0;
        sentVersion = -1;
        applied = null;
        if (n == 0) handle = null;
    }

    // ------------------------------------------------------------ piloting

    public boolean hasSeat() { var s = structure != null ? structure : shown; return s != null && s.hasSeat(); }

    /** From the pilot's client, every tick while seated. */
    void input(float pitch, float roll, float pedals, float throttle, boolean engine, boolean brake) {
        this.pitch = Math.clamp(pitch, -1, 1);
        this.roll = Math.clamp(roll, -1, 1);
        this.pedals = Math.clamp(pedals, -1, 1);
        this.throttle = Math.clamp(throttle, 0, 1);
        this.engine = engine;
        this.brake = brake;
        inputAge = 0;
    }

    /** Starts the engine (spawned with the player in the seat): idle on the ground, cruise when launched. */
    void startEngine(double throttle) {
        engine = true;
        this.throttle = (float) throttle;
        inputAge = 0;
    }
    public boolean engineOn() { return engine; }
    public float throttle() { return throttle; }

    private void pilot() {
        if (!testPilot && (++inputAge > 10 || getFirstPassenger() == null)) {
            // Nobody at the controls: centred, closed throttle, brakes on (the engine keeps idling if it ran).
            pitch = roll = pedals = 0;
            throttle = 0;
            brake = true;
        }
        double[] target = {pitch * THROW[0], roll * THROW[1], -roll * THROW[2], pedals * THROW[3]};
        for (int c = 0; c < target.length; c++) {
            // Surfaces move at most 3 radians a second.
            deflection[c] += Math.clamp(target[c] - deflection[c], -0.15, 0.15);
        }
        if (structure.rotorCount() == 0 && Arrays.stream(structure.meshChannel).allMatch(c -> c < 0) && !hasSeat()) return;
        double[] channels = deflection.clone();
        double[] input = new double[structure.rotorCount() * 2];
        for (int r = 0; r < structure.rotorCount(); r++) { input[r * 2] = throttle; input[r * 2 + 1] = engine ? 1 : 0; }
        double wheels = brake || throttle < 0.02 && engine ? BRAKING : ROLLING;
        handle.op((w, b) -> NativeSoft.control0(w, b, channels, input, wheels));
    }

    @Override protected boolean canAddPassenger(Entity passenger) { return hasSeat() && getPassengers().isEmpty(); }
    @Override public boolean shouldRiderSit() { return true; }

    /** The seat at the current pose (server) or at the pose being shown (client). */
    private double[] seatNow() {
        if (level().isClientSide) {
            if (shown == null || timeline.empty()) return null;
            if (scratch.length != shown.nodes * 3) scratch = new double[shown.nodes * 3];
            if (!timeline.sample(timeline.time(1), scratch)) return null;
            return shown.seat(scratch);
        }
        return structure == null || nodes == null ? null : structure.seat(nodes);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        double[] s = seatNow();
        if (s == null) { super.positionRider(passenger, move); return; }
        Vec3 attach = passenger.getVehicleAttachmentPoint(this);
        move.accept(passenger, s[0] - attach.x, s[1] - attach.y, s[2] - attach.z);
        // The pilot faces forwards when getting in and turns with the plane, like in a boat.
        if (level().isClientSide && shown != null && scratch.length == shown.nodes * 3) {
            double[] f = shown.forward(scratch);
            if (f != null && Math.hypot(f[0], f[2]) > 0.2) {
                float heading = (float) Math.toDegrees(Math.atan2(-f[0], f[2]));
                float turn = Float.isNaN(lastHeading) ? net.minecraft.util.Mth.wrapDegrees(heading - passenger.getYRot()) : net.minecraft.util.Mth.wrapDegrees(heading - lastHeading);
                passenger.setYRot(passenger.getYRot() + turn);
                passenger.setYHeadRot(passenger.getYHeadRot() + turn);
                lastHeading = heading;
            }
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        lastHeading = Float.NaN;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        double[] s = seatNow();
        double[] f = structure != null && nodes != null ? structure.forward(nodes) : null;
        if (s == null) return super.getDismountLocationForPassenger(passenger);
        Vec3 side = f == null ? new Vec3(1.5, 0, 0) : new Vec3(f[2], 0, -f[0]).normalize().scale(-1.6);
        return new Vec3(s[0], s[1] + 0.6, s[2]).add(side);
    }

    // ------------------------------------------------------------ terrain

    /** World collision boxes around the body, a little ahead of where it is heading. Empty sky is skipped by section. */
    private double[] terrain(ServerLevel level) { return SoftWorlds.terrain(level, bounds, 1.5 + Math.min(lastMove * 3, 6)); }

    // ------------------------------------------------------------ walking on it

    /**
     * Thin plates along the hull near query, for players to stand and walk on: the server's latest pose, or on a
     * client the pose being drawn.
     */
    public List<dev.physicalist.CompoundCollision.Box> walkBoxes(AABB query) {
        boolean client = level().isClientSide;
        SoftStructure s = client ? shown : structure;
        double[] p = client ? (shown != null && scratch.length == shown.nodes * 3 ? scratch : null) : nodes;
        if (s == null || p == null || p.length != s.nodes * 3 || s.skin == null || bounds == null || !bounds.inflate(1).intersects(query)) return List.of();
        List<dev.physicalist.CompoundCollision.Box> out = new ArrayList<>();
        double[] tip = new double[3];
        final double thick = 0.04;
        for (int i = 0; i < s.nodes; i++) {
            int o = i * SoftStructure.SKIN_STRIDE;
            int b = (int) s.skin[o], c = (int) s.skin[o + 1];
            if (b < 0) continue;
            double halfT = s.skin[o + 5], halfU = s.skin[o + 6], half = Math.max(halfT, halfU), x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            if (x + half < query.minX || x - half > query.maxX || y + half < query.minY || y - half > query.maxY || z + half < query.minZ || z - half > query.maxZ) continue;
            SoftStructure.pointInFrame(p, i, b, c, (float) s.skin[o + 2], (float) s.skin[o + 3], (float) s.skin[o + 4], tip);
            Vec3 at = new Vec3(x, y, z), n = new Vec3(tip[0] - x, tip[1] - y, tip[2] - z);
            if (n.lengthSqr() < 1e-12) continue;
            n = n.normalize();
            Vec3 t = new Vec3(p[b * 3] - x, p[b * 3 + 1] - y, p[b * 3 + 2] - z);
            t = t.subtract(n.scale(t.dot(n)));
            if (t.lengthSqr() < 1e-12) continue;
            t = t.normalize();
            Vec3 u = n.cross(t);
            // Centred on the skin node, so it stands proud of the drawn hull by only its half thickness either way.
            out.add(new dev.physicalist.CompoundCollision.Box(at, new Vec3[]{t, u, n}, new double[]{halfT, halfU, thick}));
        }
        return out;
    }

    // ------------------------------------------------------------ network

    SoftPackets.State state(boolean withComponents) {
        float[] rel = new float[nodes.length];
        for (int i = 0; i < nodes.length; i += 3) {
            rel[i] = (float) (nodes[i] - getX()); rel[i + 1] = (float) (nodes[i + 1] - getY()); rel[i + 2] = (float) (nodes[i + 2] - getZ());
        }
        byte[] bits = new byte[0];
        if (withComponents && beams != null) {
            bits = new byte[(beams.length + 7) / 8];
            for (int i = 0; i < beams.length; i++) if (beams[i]) bits[i >> 3] |= (byte) (1 << (i & 7));
        }
        return new SoftPackets.State(getId(), applied == null ? 0 : applied.tick(), getX(), getY(), getZ(), rel, version,
                withComponents ? components : new int[0], rotors, controls.clone(), bits);
    }

    /** Client: a new pose arrived. */
    public void receive(SoftPackets.State s) {
        double[] next = new double[s.nodes().length];
        for (int i = 0; i < next.length; i += 3) {
            next[i] = s.x() + s.nodes()[i]; next[i + 1] = s.y() + s.nodes()[i + 1]; next[i + 2] = s.z() + s.nodes()[i + 2];
        }
        boolean first = nodes == null;
        previous = nodes != null && nodes.length == next.length ? nodes : next;
        nodes = next;
        if (s.components().length > 0) components = s.components();
        if (s.beams().length > 0) {
            beams = new boolean[s.beams().length * 8];
            for (int i = 0; i < beams.length; i++) beams[i] = (s.beams()[i >> 3] >> (i & 7) & 1) != 0;
        }
        version = s.version();
        rotors = s.rotors();
        controls = s.controls();
        timeline.add(new SoftTimeline.Frame(s.tick(), next, s.rotors(), s.controls()));
        if (first) { timeline.tick(); centre(); xo = getX(); yo = getY(); zo = getZ(); }
    }

    private void clientTick() {
        timeline.tick();
        if (shown == null || timeline.empty()) { if (nodes != null) centre(); return; }
        if (scratch.length != shown.nodes * 3) scratch = new double[shown.nodes * 3];
        if (timeline.sample(timeline.time(1), scratch)) {
            double x = 0, y = 0, z = 0;
            int n = scratch.length / 3;
            for (int i = 0; i < scratch.length; i += 3) { x += scratch[i]; y += scratch[i + 1]; z += scratch[i + 2]; }
            if (nodes != null) { double[] keep = nodes; nodes = scratch; centre(); nodes = keep; }
            else setPos(x / n, y / n, z / n);
        }
    }

    /** Client: the structure the view built from the model (for the seat and debug drawing). */
    public void shown(SoftStructure s) { shown = s; }
    public SoftStructure shown() { return shown; }
    public SoftTimeline timeline() { return timeline; }
    /** Server position packets are ignored: clients place the body from its timeline. */
    @Override public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {}

    public double[] nodes() { return nodes; }
    public double[] previousNodes() { return previous; }
    public int[] components() { return components; }
    public boolean[] beams() { return beams; }
    public float[] rotors() { return rotors; }
    public int version() { return version; }
    public void blob(byte[] blob) { this.blob = blob; }
    public void userScale(double scale) { this.userScale = scale; }
    @Override public void onClientRemoval() { super.onClientRemoval(); dev.physicalist.client.SoftBodyView.release(this); }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        SoftPackets.sendModel(player, this);
        if (nodes != null) PacketDistributor.sendToPlayer(player, state(true));
    }

    /** Starts over where it was first spawned, undamaged, doors shut, propeller standing (it spins up again if the engine is on). */
    public void respawn() {
        if (structure == null || !(level() instanceof ServerLevel)) return;
        if (handle != null) handle.remove();
        handle = null;
        fresh = true;
        place(origin);
        Arrays.fill(deflection, 0);
        sentVersion = -1;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (player.getItemInHand(hand).is(PhysicalistContent.PHYSICALIST.get()) && player.isShiftKeyDown()) {
            discard();
            player.displayClientMessage(Component.translatable("message.physicalist_library.soft_removed"), true);
            return InteractionResult.CONSUME;
        }
        int door = aimedDoor(player);
        if (door >= 0) { toggle(door); return InteractionResult.CONSUME; }
        if (!player.isShiftKeyDown() && canAddPassenger(player) && player.startRiding(this)) {
            player.displayClientMessage(Component.translatable("message.physicalist_library.soft_seated"), true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!level().isClientSide && attacker instanceof Player p && p.getMainHandItem().is(PhysicalistContent.PHYSICALIST.get())) discard();
        return true;
    }

    /** Door closest to the player's line of sight, if the line passes within a block of it. */
    int aimedDoor(Player player) {
        if (structure == null || nodes == null) return -1;
        Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
        int best = -1;
        double bestDistance = 1.0;
        for (int d = 0; d < structure.doors.size(); d++) {
            if (fallen[d]) continue;
            for (int i : structure.doors.get(d).nodes()) {
                Vec3 p = new Vec3(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2]).subtract(eye);
                double along = p.dot(look);
                if (along < 0 || along > 8) continue;
                double off = p.subtract(look.scale(along)).length();
                if (off < bestDistance) { bestDistance = off; best = d; }
            }
        }
        return best;
    }

    /** Open a shut door; shut an open (or closing) one. Shutting swings it back and latches it once it is in place. */
    void toggle(int d) {
        if (handle == null) return;
        var door = structure.doors.get(d);
        open[d] = !open[d];
        closing[d] = open[d] ? -1 : 0;
        held[d] = false;
        boolean opening = open[d];
        handle.op((w, b) -> { NativeSoft.latch0(w, b, door.hold(), false); if (opening) NativeSoft.latch0(w, b, door.latch(), false); });
        if (open[d]) swing(door, 2.2);
    }

    /** Doors on their way shut: keep swinging them in, latch when the free edge is back home. */
    private void closeDoors() {
        for (int d = 0; d < open.length; d++) {
            if (fallen[d]) continue;
            var door = structure.doors.get(d);
            int home = (int) structure.beams[door.latch() * SoftStructure.BEAM_STRIDE + 1];
            if (open[d] && !held[d]) {
                // At the end of its swing the check strap catches it, so it does not bounce back shut.
                int end = (int) structure.beams[door.hold() * SoftStructure.BEAM_STRIDE + 1];
                if (node(door.free()).distanceTo(node(end)) < door.length() * 0.25) {
                    handle.op((w, b) -> NativeSoft.latch0(w, b, door.hold(), true));
                    held[d] = true;
                }
                continue;
            }
            if (open[d] || closing[d] < 0) continue;
            if (node(door.free()).distanceTo(node(home)) < door.length() * 0.12 || closing[d] > 100) {
                handle.op((w, b) -> NativeSoft.latch0(w, b, door.latch(), true));
                closing[d] = -1;
                continue;
            }
            // Speed control on the free edge: about 1.5 m/s inwards, slowing down near the frame.
            Vec3 a = node(door.hingeA()), axis = node(door.hingeB()).subtract(a).normalize();
            Vec3 r = node(door.free()).subtract(a);
            r = r.subtract(axis.scale(r.dot(axis)));
            Vec3 in = axis.cross(r).normalize().scale(-door.turn());
            int f = door.free();
            double dt = Math.max(1, applied.tick() - previousTick) * 0.05;
            Vec3 velocity = node(f).subtract(new Vec3(previous[f * 3], previous[f * 3 + 1], previous[f * 3 + 2])).scale(1 / dt);
            double gap = node(f).distanceTo(node(home)) / door.length();
            double target = Math.clamp(gap * 3, 0.4, 1.5);
            swing(door, -Math.clamp(target - velocity.dot(in), -1, 1));
            closing[d]++;
        }
    }

    /** Pushes every door node around the hinge (outwards for positive speed), the free edge the most. */
    private void swing(SoftStructure.Door door, double speed) {
        Vec3 a = node(door.hingeA()), axis = node(door.hingeB()).subtract(a).normalize();
        List<int[]> who = new ArrayList<>();
        List<double[]> dv = new ArrayList<>();
        for (int i : door.nodes()) {
            Vec3 r = node(i).subtract(a);
            r = r.subtract(axis.scale(r.dot(axis)));
            double reach = Math.min(1, r.length() / Math.max(door.length(), 1e-3));
            Vec3 dir = axis.cross(r).normalize().scale(door.turn() * speed * reach);
            who.add(new int[]{i});
            dv.add(new double[]{dir.x, dir.y, dir.z});
        }
        handle.op((w, b) -> { for (int k = 0; k < who.size(); k++) NativeSoft.push0(w, b, who.get(k), dv.get(k)); });
    }

    /** Changes the speed of some nodes at once (a blow, a gust on one part). */
    void kick(int[] who, double[] dv) {
        if (handle == null || who.length == 0) return;
        int[] w2 = who.clone(); double[] d2 = dv.clone();
        handle.op((w, b) -> NativeSoft.push0(w, b, w2, d2));
    }

    private Vec3 node(int i) { return new Vec3(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2]); }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (!level().isClientSide && handle != null) { handle.remove(); handle = null; }
    }

    @Override public boolean shouldBeSaved() { return blob.length > 0 && blob.length <= 8 << 20 && super.shouldBeSaved(); }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putByteArray("Model", blob);
        tag.putDouble("Scale", userScale);
        tag.putFloat("Yaw", yaw);
        tag.putDouble("OriginX", origin.x); tag.putDouble("OriginY", origin.y); tag.putDouble("OriginZ", origin.z);
        tag.putDouble("LaunchX", launch.x); tag.putDouble("LaunchY", launch.y); tag.putDouble("LaunchZ", launch.z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        blob = tag.getByteArray("Model");
        userScale = tag.contains("Scale") ? tag.getDouble("Scale") : 1;
        yaw = tag.getFloat("Yaw");
        origin = new Vec3(tag.getDouble("OriginX"), tag.getDouble("OriginY"), tag.getDouble("OriginZ"));
        launch = new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ"));
        structure = null;
    }
}
