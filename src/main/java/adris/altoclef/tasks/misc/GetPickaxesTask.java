package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import net.minecraft.item.Items;

public class GetPickaxesTask extends Task {
	
	private final Task _getPicks = TaskCatalogue
			.getItemTask(new ItemTarget(Items.IRON_PICKAXE, 3));

	public GetPickaxesTask() {
		
	}
	
	@Override
	protected void onStart(AltoClef mod) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected Task onTick(AltoClef mod) {
		return _getPicks;
	}

	@Override
	protected void onStop(AltoClef mod, Task interruptTask) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected boolean isEqual(Task other) {
		// TODO Auto-generated method stub
		return other instanceof GetPickaxesTask;
	}

	@Override
	protected String toDebugString() {
		// TODO Auto-generated method stub
		return "Gathering pickaxes for long mining journey!";
	}
	
	@Override
	public boolean isFinished(AltoClef mod) {
		return _getPicks.isFinished(mod);
	}
	
}
