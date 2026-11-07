package dev.physicalist;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Surface forces, not attitude corrections: point flow includes omega cross radius. */
public final class SurfaceAerodynamics {
    public record Wrench(Vec3 force,Vec3 torque) {
        public static final Wrench ZERO=new Wrench(Vec3.ZERO,Vec3.ZERO);
        public Wrench add(Wrench other){return new Wrench(force.add(other.force),torque.add(other.torque));}
        public Wrench scale(double amount){return new Wrench(force.scale(amount),torque.scale(amount));}
        public static Wrench atPoint(Vec3 force,Vec3 point,Vec3 center){return new Wrench(force,point.subtract(center).cross(force));}
    }
    /** v is metres/second, omega radians/second, arm metres. No gravity or artificial horizon torque. */
    public static Wrench surface(AeroSurface s,Vec3 velocity,Vec3 omega,Vec3 arm,
            Vec3 chord,Vec3 span,double density){
        Vec3 normal=chord.cross(span);normal=normal.scale(1/normal.length());
        Vec3 flow=velocity.add(omega.cross(arm));
        Vec3 section=flow.subtract(span.scale(flow.dot(span)));double speed=section.length();
        if(speed<1e-6||density<=0)return Wrench.ZERO;
        Vec3 travel=section.scale(1/speed);
        double alpha=Math.atan2(-section.dot(normal),section.dot(chord))+s.incidenceRadians();
        alpha=Math.atan2(Math.sin(alpha),Math.cos(alpha));
        double cl=Math.clamp(s.liftSlope()*alpha,-s.maximumLift(),s.maximumLift());
        double stall=Math.clamp((Math.abs(alpha)-s.stallRadians())/(Math.PI/2-s.stallRadians()),0,1);
        stall=stall*stall*(3-2*stall);
        cl=cl*(1-stall)+Math.sin(2*alpha)*s.maximumLift()*.5*stall;
        double cd=s.zeroLiftDrag()+cl*cl/(Math.PI*s.aspectRatio()*s.efficiency())+1.35*Math.pow(Math.sin(alpha),2);
        Vec3 lift=normal.subtract(travel.scale(normal.dot(travel)));double length=lift.length();
        if(length>1e-6)lift=lift.scale(1/length);else lift=Vec3.ZERO;
        double q=.5*density*speed*speed*s.area();
        Vec3 force=lift.scale(q*cl).subtract(travel.scale(q*cd));
        return new Wrench(force,arm.cross(force));
    }
    public static Wrench forces(PhysicsBody body){
        Vec3 v=body.entity().getDeltaMovement().subtract(body.windVelocity()).scale(20),omega=body.angularVelocity().scale(20);
        Vec3 center=body.centerOfMass();Wrench result=Wrench.ZERO;
        List<AeroSurface> authored=body.aerodynamicSurfaces();
        if(!authored.isEmpty()){
            Quaternionf q=body.nativeOrientation();
            for(var s:authored){Vec3 point=body.entity().position().add(rotate(q,s.point()));
                result=result.add(surface(s,v,omega,point.subtract(center),rotate(q,s.chord()),rotate(q,s.span()),body.airDensity()));}
        }else{
            var settings=body.autoAeroProfile();Vec3 forward=body.forward(),up=body.up();
            for(var box:body.collisionBoxes()){
                if(!AutoAerodynamics.isLiftingSurface(box,forward,settings))continue;
                double[] h=box.half();int thin=0;for(int i=1;i<3;i++)if(h[i]<h[thin])thin=i;
                Vec3 normal=box.axes()[thin];boolean horizontal=Math.abs(normal.dot(up))>=.5;
                if(horizontal&&normal.dot(up)<0)normal=normal.scale(-1);
                Vec3 chord=forward.subtract(normal.scale(forward.dot(normal)));chord=chord.scale(1/chord.length());
                Vec3 span=normal.cross(chord);span=span.scale(1/span.length());
                int a=(thin+1)%3,b=(thin+2)%3;double area=4*h[a]*h[b];
                double width=2*(h[a]*Math.abs(box.axes()[a].dot(span))+h[b]*Math.abs(box.axes()[b].dot(span)));
                var s=AeroSurface.wing(Vec3.ZERO,new Vec3(0,0,1),new Vec3(1,0,0),area,Math.max(.5,width*width/area),horizontal?settings.incidenceDegrees():0);
                result=result.add(surface(s,v,omega,box.center().subtract(center),chord,span,body.airDensity()));
            }
        }
        // Bound total acceleration for game-sized inferred plates, without converting moments into Euler corrections.
        double maximum=body.autoAeroProfile().maximumLift()*body.aerodynamicMass()*400;
        if(maximum>0&&result.force().length()>maximum)result=result.scale(maximum/result.force().length());
        return result;
    }
    private static Vec3 rotate(Quaternionf q,Vec3 p){var v=q.transform(new Vector3f((float)p.x,(float)p.y,(float)p.z));return new Vec3(v.x,v.y,v.z);}
    private SurfaceAerodynamics(){}
}
