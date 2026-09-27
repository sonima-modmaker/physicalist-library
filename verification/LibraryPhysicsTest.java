import dev.physicalist.Aerodynamics;
import dev.physicalist.CompoundCollision;
import dev.physicalist.PhysicsProfile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class LibraryPhysicsTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        PhysicsProfile p = new PhysicsProfile(.045, .002, .0006, .014, .008,
                .003, .038, .0014, .010, .0005, .003, .0015, .009, .965,
                .12, .35, 16, .001, .35, .08, 320);
        Vec3 velocity = new Vec3(0, 0, -5.5);
        Vec3 forward = new Vec3(0, 0, -1);
        Vec3 up = new Vec3(0, 1, 0);
        var intact = Aerodynamics.step(velocity, Vec3.ZERO, forward, up, 1, 0, 1, false, p);
        var folded = Aerodynamics.step(velocity, Vec3.ZERO, forward, up, .5, .5, 1, false, p);
        check(intact.velocity().length() > 5.3, "Momentum vanished");
        check(folded.velocity().y < intact.velocity().y, "Folded wing makes full lift");
        check(folded.angular().length() > intact.angular().length(), "Missing wing creates no moment");
        var ground = CompoundCollision.box(new AABB(-8, -1, -8, 8, 0, 8));
        var fin = new CompoundCollision.Box(new Vec3(0, 2, 0),
                new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)},
                new double[]{2, .025, .5});
        var landed = new CompoundCollision.Box(new Vec3(0, -1, 0), fin.axes(), fin.half());
        check(CompoundCollision.swept(fin, landed, ground) != null, "Fast fin tunnels through terrain");
        check(CompoundCollision.contact(fin, ground) == null, "Airborne fin contacts terrain");
        System.out.println("PASS: configurable aero, folded wing, swept thin-part collision");
    }
}
