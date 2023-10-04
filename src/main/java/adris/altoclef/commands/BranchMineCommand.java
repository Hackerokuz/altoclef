package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.construction.BranchMiningTask;

public class BranchMineCommand extends Command {

	public BranchMineCommand() {
        super("branchMine", "Create a branch mine from the current position in direction bot is currently looking at");
	}
	
	@Override
    protected void call(AltoClef mod, ArgParser parser) {
		mod.runUserTask(new BranchMiningTask(mod.getPlayer().getBlockPos(), mod.getPlayer().getMovementDirection()), this::finish);
    }

}
