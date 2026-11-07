package dev.physicalist;
import net.minecraft.world.phys.Vec3;
/** Reusable SI rotor load. A controller chooses RPM; this class supplies force and reaction torque. */
public final class PropellerForce {
    public static SurfaceAerodynamics.Wrench at(Vec3 center,Vec3 axis,Vec3 bodyCenter,double rpm,
            double diameter,double thrustCoefficient,double torqueCoefficient,double density,int spin){
        if(!RigidBodyState.finite(center)||!RigidBodyState.finite(axis)||axis.lengthSqr()<1e-12
                ||!Double.isFinite(rpm)||rpm<0||!Double.isFinite(diameter)||diameter<=0
                ||!Double.isFinite(thrustCoefficient)||thrustCoefficient<0||!Double.isFinite(torqueCoefficient)||torqueCoefficient<0
                ||!Double.isFinite(density)||density<0||(spin!=1&&spin!=-1))throw new IllegalArgumentException("Invalid propeller parameters");
        Vec3 direction=axis.scale(1/axis.length());double turns=rpm/60;
        double thrust=thrustCoefficient*density*turns*turns*Math.pow(diameter,4);
        double reaction=torqueCoefficient*density*turns*turns*Math.pow(diameter,5)*spin;
        var force=direction.scale(thrust);
        return new SurfaceAerodynamics.Wrench(force,center.subtract(bodyCenter).cross(force).add(direction.scale(reaction)));
    }
    private PropellerForce(){}
}
