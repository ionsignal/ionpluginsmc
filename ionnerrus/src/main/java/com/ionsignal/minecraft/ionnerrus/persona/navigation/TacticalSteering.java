package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.SteeringResult.MovementType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.bukkit.Location;

import java.util.Optional;

/**
 * Stateless utility that performs short-range geometric analysis to determine
 * the appropriate movement type (WALK, JUMP, DROP) for direct engagement.
 * 
 * This replaces "blind walking" in Fast Mode with reactive physics awareness.
 */
public final class TacticalSteering {
    private static final double MAX_JUMP_HEIGHT = 1.25;
    private static final double LOOKAHEAD_DIST = 1.0;

    private TacticalSteering() {
    }

    /**
     * Computes the tactical movement type required to reach the target from the entity's current
     * position.
     * 
     * @param entity
     *            The NMS entity wrapper.
     * @param target
     *            The target location.
     * @return Optional containing the MovementType, or Empty if the path is blocked/unsafe.
     */
    @SuppressWarnings("null")
    public static Optional<MovementType> compute(PersonaEntity entity, Location target) {
        Level level = entity.level();
        Vec3 start = entity.position();
        // Manual math to reduce Vec3 allocations
        double dx = target.getX() - start.x;
        double dz = target.getZ() - start.z;
        double distSqr = dx * dx + dz * dz;
        if (distSqr < 0.01) {
            // We are horizontally aligned, check vertical
            if (target.getY() > entity.getY() + NavigationHelper.MAX_STEP_HEIGHT) {
                // Check headroom before recommending JUMP to prevent "bunny-hopping"
                // Construct the bounding box at the target Y level to see if the entity fits.
                Vec3 targetPos = new Vec3(entity.getX(), target.getY(), entity.getZ());
                AABB targetBox = entity.getDimensions(Pose.STANDING).makeBoundingBox(targetPos);
                if (level.noCollision(entity, targetBox)) {
                    return Optional.of(MovementType.JUMP);
                } else {
                    return Optional.of(MovementType.WALK);
                }
            } else if (target.getY() < entity.getY() - 0.5) {
                return Optional.of(MovementType.DROP); // Drop down to target
            }
            return Optional.of(MovementType.WALK);
        }
        // Manual normalization
        double dist = Math.sqrt(distSqr);
        double scale = Math.min(dist, LOOKAHEAD_DIST) / dist;
        Vec3 horizontalMotion = new Vec3(dx * scale, 0, dz * scale);
        // Project the entity's bounding box forward to detect collisions
        AABB currentBox = entity.getBoundingBox();
        AABB projectedBox = currentBox.move(horizontalMotion);
        if (!level.noCollision(entity, projectedBox)) {
            // If there is a collision in the projected path
            return analyzeObstruction(entity, level, projectedBox, currentBox);
        }
        // If path is clear horizontally, check if we are walking off a ledge
        return analyzeFloor(entity, level, horizontalMotion);
    }

    @SuppressWarnings("null")
    private static Optional<MovementType> analyzeObstruction(
            PersonaEntity entity,
            Level level,
            AABB projectedBox,
            AABB currentBox) {
        // Only check Block Collisions (Cheaper, prevents jumping over mobs)
        Iterable<VoxelShape> blockCollisions = level.getBlockCollisions(entity, projectedBox);
        double maxObstacleY = -Double.MAX_VALUE;
        boolean hitSomething = false;
        for (VoxelShape shape : blockCollisions) {
            if (!shape.isEmpty()) {
                maxObstacleY = Math.max(maxObstacleY, shape.max(net.minecraft.core.Direction.Axis.Y));
                hitSomething = true;
            }
        }
        if (!hitSomething) {
            return Optional.of(MovementType.WALK); // False positive or non-blocking
        }
        // Calculate height relative to entity feet
        double obstacleHeight = maxObstacleY - entity.getY();
        // Case A: Auto-Step (Slabs, Carpets)
        if (obstacleHeight <= NavigationHelper.MAX_STEP_HEIGHT) {
            return Optional.of(MovementType.WALK);
        }
        // Case B: Jumpable (Logs, Fences if < 1.25)
        if (obstacleHeight <= MAX_JUMP_HEIGHT) {
            // Check Head Clearance for the jump apex
            // We need roughly obstacleHeight + 1.8m of space
            AABB headCheck = currentBox.move(0, obstacleHeight + 0.1, 0);
            if (level.noCollision(entity, headCheck)) {
                return Optional.of(MovementType.JUMP);
            }
        }
        // Case C: Wall / Ceiling Obstruction
        return Optional.empty();
    }

    private static Optional<MovementType> analyzeFloor(PersonaEntity entity, Level level, Vec3 motion) {
        // Use MutableBlockPos to reduce allocations
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        mutablePos.set(entity.getX() + motion.x, entity.getY(), entity.getZ() + motion.z);
        // Check feet level
        if (hasSupport(level, mutablePos)) {
            return Optional.of(MovementType.WALK);
        }
        // Check 1 block down
        mutablePos.move(net.minecraft.core.Direction.DOWN);
        if (hasSupport(level, mutablePos)) {
            return Optional.of(MovementType.WALK);
        }
        // It's a drop. Check landing safety (2-4 blocks down)
        for (int i = 0; i < 3; i++) {
            mutablePos.move(net.minecraft.core.Direction.DOWN);
            if (hasSupport(level, mutablePos)) {
                return Optional.of(MovementType.DROP);
            }
        }
        // Void or deep fall -> Unsafe
        return Optional.empty();
    }

    @SuppressWarnings("null")
    private static boolean hasSupport(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty();
    }
}