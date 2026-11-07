package dev.physicalist;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Continuous Y-X-Z display angles for a world-space rigid-body rotation. */
public final class BodyRotation {
    private static float near(float value, float previous) {
        return previous + (float) Math.atan2(Math.sin(value - previous), Math.cos(value - previous));
    }

    public static Vector3f angles(Quaternionf rotation, Vector3f previous) {
        Quaternionf q = new Quaternionf(rotation).normalize();
        Vector3f right = q.transform(new Vector3f(1, 0, 0));
        Vector3f up = q.transform(new Vector3f(0, 1, 0));
        Vector3f back = q.transform(new Vector3f(0, 0, 1));
        double horizontal = Math.hypot(back.x, back.z);
        float pitch = (float) Math.atan2(-back.y, horizontal), yaw, roll;
        if (horizontal < 1e-6) {
            yaw = previous.y;
            Quaternionf residual = new Quaternionf().rotateY(yaw).rotateX(pitch).invert().mul(q);
            roll = 2 * (float) Math.atan2(residual.z, residual.w);
        } else {
            yaw = (float) Math.atan2(back.x, back.z);
            roll = (float) Math.atan2(right.y, up.y);
        }
        Vector3f first = new Vector3f(near(pitch, previous.x), near(yaw, previous.y), near(roll, previous.z));
        Vector3f second = new Vector3f(near((float) Math.PI - pitch, previous.x),
                near(yaw + (float) Math.PI, previous.y), near(roll + (float) Math.PI, previous.z));
        return first.distanceSquared(previous) <= second.distanceSquared(previous) ? first : second;
    }

    private BodyRotation() {}
}
