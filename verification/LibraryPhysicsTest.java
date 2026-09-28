import dev.physicalist.Aerodynamics;
import dev.physicalist.AutoAerodynamics;
import dev.physicalist.AutoAeroProfile;
import dev.physicalist.CompoundCollision;
import dev.physicalist.PhysicsProfile;
import java.util.List;
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
        AutoAeroProfile auto = new AutoAeroProfile(.18, .2, 1.2, 5, .012, .24, .008, .20, .15, .03);
        Vec3 ahead = new Vec3(0, 0, 1);
        var hull = new CompoundCollision.Box(Vec3.ZERO, fin.axes(), new double[]{1, .7, 2});
        var left = new CompoundCollision.Box(new Vec3(-1.5, 0, 0), fin.axes(), new double[]{1, .025, .5});
        var right = new CompoundCollision.Box(new Vec3(1.5, 0, 0), fin.axes(), new double[]{1, .025, .5});
        var door = new CompoundCollision.Box(Vec3.ZERO, fin.axes(), new double[]{.025, .8, 1.2});
        check(AutoAerodynamics.isLiftingSurface(left, ahead, auto), "Flat wing not detected");
        check(!AutoAerodynamics.isLiftingSurface(hull, ahead, auto), "Hull falsely treated as wing");
        check(!AutoAerodynamics.isLiftingSurface(door, ahead, auto), "Door falsely treated as wing");
        var foldedWing = new CompoundCollision.Box(left.center(), left.axes(),
                new double[]{1, .3, .5});
        check(!AutoAerodynamics.isLiftingSurface(foldedWing, ahead, auto),
                "Thick folded wing still produces lift");
        var noWing = AutoAerodynamics.step(new Vec3(0, 0, 4), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull), 1, false, p, auto);
        var winged = AutoAerodynamics.step(new Vec3(0, 0, 4), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull, left, right), 1, false, p, auto);
        check(winged.velocity().y > 0 && noWing.velocity().y < 0,
                "Speed and collision wings do not produce takeoff lift");
        check(winged.angular().length() < 1e-8, "Balanced wings add unwanted roll");
        var oneWing = AutoAerodynamics.step(new Vec3(0, 0, 4), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull, right), 1, false, p, auto);
        check(oneWing.angular().length() > .001, "Missing wing creates no aerodynamic roll");
        var heavy = AutoAerodynamics.step(new Vec3(0, 0, 4), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull, left, right), 5, false, p, auto);
        check(heavy.velocity().y < winged.velocity().y, "Mass does not reduce lift");
        var autoFolded = AutoAerodynamics.step(new Vec3(0, 0, 4), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull, foldedWing), 1, false, p, auto);
        check(Math.abs(autoFolded.velocity().y - noWing.velocity().y) < 1e-8,
                "Folding collision shape did not remove lift");
        var verticalWing = new CompoundCollision.Box(left.center(),
                new Vec3[]{new Vec3(0, 1, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1)}, left.half());
        var rotated = AutoAerodynamics.step(new Vec3(0, 0, 4), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull, verticalWing), 1, false, p, auto);
        check(Math.abs(rotated.velocity().y - noWing.velocity().y) < 1e-8,
                "Folded vertical wing still produces horizontal-flight lift");
        var freeFall = AutoAerodynamics.step(new Vec3(0, -3, 0), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull), 1, false, p, auto);
        var wingFall = AutoAerodynamics.step(new Vec3(0, -3, 0), Vec3.ZERO, ahead, up,
                Vec3.ZERO, List.of(hull, left, right), 1, false, p, auto);
        check(wingFall.velocity().y > freeFall.velocity().y,
                "Broad wings do not resist a fall");
        System.out.println("PASS: manual and automatic aero, wing balance, mass, fall drag, swept collision");
    }
}
