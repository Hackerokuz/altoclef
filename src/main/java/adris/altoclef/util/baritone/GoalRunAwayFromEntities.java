package adris.altoclef.util.baritone;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import net.minecraft.entity.Entity;

import java.util.Optional;

public abstract class GoalRunAwayFromEntities implements Goal {

    private final AltoClef _mod;
    private final double _distance;
    private final boolean _xzOnly;

    // Higher: We will move more directly away from each entity
    // Too high: We will refuse to take alternative, faster paths and will dig straight away.
    // Lower: We will in general move far away from an entity, allowing the ocassional closer traversal.
    // Too low: We will just run straight into the entity to go past it.
    private final double _penaltyFactor;

    public GoalRunAwayFromEntities(AltoClef mod, double distance, boolean xzOnly, double penaltyFactor) {
        _mod = mod;
        _distance = distance;
        _xzOnly = xzOnly;
        _penaltyFactor = penaltyFactor;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (getEntities(_mod).isPresent()) {
            Entity entity = getEntities(_mod).get();
            if (entity.isAlive()) {
                double sqDistance;
                if (_xzOnly) {
                    sqDistance = entity.getPos().subtract(x, y, z).multiply(1, 0, 1).lengthSquared();
                } else {
                    sqDistance = entity.squaredDistanceTo(x, y, z);
                }
                return !(sqDistance < _distance * _distance);
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        // The lower the cost, the better.
        double costSum = 0;
        int counter = 0;
        if (getEntities(_mod).isPresent()) {
            Entity entity = getEntities(_mod).get();
            counter++;
            if (entity.isAlive()) {
                double cost = getCostOfEntity(entity, x, y, z);
                if (cost != 0) {
                    // We want the CLOSER entities to have a bigger weight than the further ones.
                    costSum += 1 / cost;
                } else {
                    // Bad >:(
                    costSum += 1000;
                }
                ;
            }
        }
        if (counter > 0) {
            costSum /= counter;
        }
        return costSum * _penaltyFactor;
    }

    protected abstract Optional<Entity> getEntities(AltoClef mod);

    // Virtual
    protected double getCostOfEntity(Entity entity, int x, int y, int z) {
        double heuristic = 0;
        if (!_xzOnly) {
            heuristic += GoalYLevel.calculate(entity.getBlockPos().getY(), y);
        }
        heuristic += GoalXZ.calculate(entity.getBlockPos().getX() - x, entity.getBlockPos().getZ() - z);
        return heuristic; //entity.squaredDistanceTo(x, y, z);
    }
}
