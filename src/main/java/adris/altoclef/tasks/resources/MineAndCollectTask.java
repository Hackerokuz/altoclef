package adris.altoclef.tasks.resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.BranchMiningTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.misc.GetPickaxesTask;
import adris.altoclef.tasks.movement.GetToYTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.CursorSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MiningToolItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);

    private final MineOrCollectTask _subtask;
    
    private boolean _wasWarnned = false;
    

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets);
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.getBlockFromItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
    	
    	if(mod.getClientBaritoneSettings().legitMine.value && !_wasWarnned)
    	{
    		Debug.logWarning("Please make sure that block tracker settings are as follows: "
			+ "\n" +
    		"scanInterval: 1.0"
    		+ "\n" +
    		"scanIntervalWhenNewBlocksFound: 0.3"
    		+ "\n" +
    		"maxTotalCacheSize: 45000"
    		+ "\n" +
    		"maxCacheSizePerBlockType: 600"
    		+ "\n" +
    		"Waring: These tracker settings can cause a lot of lag...");
    		_wasWarnned = true;
    	}
    	
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_blocksToMine);

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        _subtask.resetSearch();
//    	mod.getBehaviour().setBlockBreakAdditionalPenalty(0.0); mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.value = 0.0;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (!StorageHelper.miningRequirementMet(mod, _requirement)) {
            return new SatisfyMiningRequirementTask(_requirement);
        }

        if (_subtask.isMining()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod) && !mod.getBlockTracker().anyFound(_blocksToMine)) {
            return getToCorrectDimensionTask(mod);
        }
        

        return _subtask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(_blocksToMine);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Mine And Collect";
    }

    private void makeSureToolIsEquipped(AltoClef mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().needsToEat()) {
            assert MinecraftClient.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                Item item = cursorStack.getItem();
                if (item.isSuitableFor(mod.getWorld().getBlockState(_subtask.miningPos()))) {
                    // Our cursor stack would help us mine our current block
                    Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (item instanceof MiningToolItem) {
                        if (currentlyEquipped instanceof MiningToolItem currentPick) {
                            MiningToolItem swapPick = (MiningToolItem) item;
                            if (swapPick.getMaterial().getMiningLevel() > currentPick.getMaterial().getMiningLevel()) {
                                // We can equip a better pickaxe.
                                mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            mod.getSlotHandler().forceEquipSlot(CursorSlot.SLOT);
                        }
                    }
                }
            }
            _cursorStackTimer.reset();
        }
    }
    
    protected static boolean isOre(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.COAL_ORE
        		|| block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.IRON_ORE
                || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.GOLD_ORE
                || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE
                || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.LAPIS_ORE
                || block == Blocks.DEEPSLATE_LAPIS_ORE
                || block == Blocks.DIAMOND_ORE
                || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.EMERALD_ORE
                || block == Blocks.DEEPSLATE_EMERALD_ORE
                || block == Blocks.NETHER_QUARTZ_ORE;
    }
    protected static boolean isNewPickaxeRequired(AltoClef mod)
    {
    	// We dont care about the wooden one as its just way too weak...
    	Item[] PICKAXES = new Item[] { 
    			Items.STONE_PICKAXE,
    			Items.IRON_PICKAXE,
    			Items.DIAMOND_PICKAXE,
    			Items.NETHERITE_PICKAXE,
    			Items.GOLDEN_PICKAXE};
    	
    	if(mod.getItemStorage().getSlotsWithItemScreen(
//    			Items.WOODEN_PICKAXE, 
//    			Items.STONE_PICKAXE,
//    			Items.IRON_PICKAXE,
//    			Items.DIAMOND_PICKAXE,
//    			Items.NETHERITE_PICKAXE,
//    			Items.GOLDEN_PICKAXE
    			PICKAXES
			).size() == 0)
    	{
    		return true;
    	}

    	for (Item pickaxe : PICKAXES)
    	{
    		if(mod.getItemStorage().getSlotsWithItemScreen(pickaxe).size() > 0)
    		{
				for(Slot slot : mod.getItemStorage().getSlotsWithItemScreen(pickaxe))
				{
					if(
							slot.getInventorySlot() > -1 && 
						mod.getItemStorage().getItemStacksPlayerInventory(false).get(
								slot.getInventorySlot()
						).getDamage() < (pickaxe.getMaxDamage() * 0.6)
					) {
		    			return false;
					}
				}
			}
    	}
    	return true;
    }
    

    private static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private final Block[] _blocks;
        private final ItemTarget[] _targets;
        private final Set<BlockPos> _blacklist = new HashSet<>();
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
        private final MovementProgressChecker _searchProgressChecker = new MovementProgressChecker(6, 15.0, 0.5, 0.001, 2);
        private final Task _pickupTask;
        private BlockPos _miningPos;
        private Task _searchTask;
        private boolean _isOre;
        private TimerGame _desperateSettingsTimer = new TimerGame(720);
        
        private final Task _getPicksTask = new GetPickaxesTask();

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            _blocks = blocks;
            _targets = targets;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
            _searchTask = null;
            _isOre = isOre(blocks[0].getDefaultState());
            _desperateSettingsTimer.forceElapse();
            
        }

        @Override
        protected Vec3d getPos(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                return item.getPos();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected Optional<Object> getClosestTo(AltoClef mod, Vec3d pos) {
        	Optional<BlockPos> closestBlock = mod.getBlockTracker().getNearestTracking(pos, check -> {
                if(mod.getClientBaritoneSettings().legitMine.value 
                		&& _isOre
                		)
                {
                    return WorldHelper.canBreak(mod, check) 
    	            		&& isNextToAir(mod, check) && isBlockVisible(check);
                }

              if (_blacklist.contains(check)) return false;
              if (mod.getBlockTracker().unreachable(check)) return false;
            	return WorldHelper.canBreak(mod, check);
            }, _blocks);
            Optional<ItemEntity> closestDrop = Optional.empty();
            if (mod.getEntityTracker().itemDropped(_targets)) {
                closestDrop = mod.getEntityTracker().getClosestItemDrop(pos, _targets);
            }

            double blockSq = closestBlock.isEmpty() ? Double.POSITIVE_INFINITY : closestBlock.get().getSquaredDistance(pos);
            double dropSq = closestDrop.isEmpty() ? Double.POSITIVE_INFINITY : closestDrop.get().squaredDistanceTo(pos) + 10; // + 5 to make the bot stop mining a bit less

            // We can't mine right now.
            if (mod.getExtraBaritoneSettings().isInteractionPaused()) {
                return closestDrop.map(Object.class::cast);
            }


            if (dropSq <= blockSq) {
                return closestDrop.map(Object.class::cast);
            } else {
                return closestBlock.map(Object.class::cast);
            }
        }

        @Override
        protected Vec3d getOriginPos(AltoClef mod) {
            return mod.getPlayer().getPos();
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            	_progressChecker.reset();
            }
            if (_miningPos != null && !_progressChecker.check(mod)) {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                if(!mod.getClientBaritoneSettings().legitMine.value || !_isOre)
                {
                    Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
	                mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2);
	                _blacklist.add(_miningPos);
                }
                _miningPos = null;
                _progressChecker.reset();
                _desperateSettingsTimer.reset();
            }
            else
            if(!_searchProgressChecker.check(mod) && _miningPos == null)
            {
            	_searchProgressChecker.reset();
                _desperateSettingsTimer.reset();
            }
            
            if(mod.getClientBaritoneSettings().legitMine.value
					&& !_desperateSettingsTimer.elapsed())
            {
                mod.getBehaviour().setBlockBreakAdditionalPenalty(0.0);
	            mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.value = 0.0;
	            mod.getBehaviour().setBlockPlacePenalty(0.0);
	            mod.getClientBaritoneSettings().blockPlacementPenalty.value = 0.0;
	            mod.getClientBaritoneSettings().costHeuristic.value = 80.5;
            }
            
            if(
            		mod.getClientBaritoneSettings().legitMine.value && (_getPicksTask.isActive() && !_getPicksTask.isFinished(mod)
        		|| _isOre
    			&& isNewPickaxeRequired(mod))
    		) {
            	if(!(mod.getUserTaskChain().getCurrentTask() instanceof GetPickaxesTask))
            	{
            		Debug.logMessage("We are getting some pickaxes to continue mining... We will get back to the previous task in a moment...");
            		Debug.logMessage("Legit mine is resource intensive...");
                	Task CurrentTask = mod.getUserTaskChain().getCurrentTask();
	            	mod.runUserTask(_getPicksTask, () -> {
	            		if(CurrentTask != null && _getPicksTask.isFinished(mod))
	                	{
	            			mod.runUserTask(CurrentTask);
	                	}
	            	});
            	}
            }
            
            if(_miningPos != null)
            {
                return super.onTick(mod);
            }

			if(mod.getClientBaritoneSettings().legitMine.value && _miningPos == null
					&& !(mod.getUserTaskChain().getCurrentTask() instanceof TimeoutWanderTask)
    		 )
            {
	            // Keeping penalty low allows the bot to mine through walls to the next cave. Does not work with high numbers...
	            mod.getBehaviour().setBlockBreakAdditionalPenalty(12.0);
	            mod.getClientBaritoneSettings().blockBreakAdditionalPenalty.value = 12.0;
	            mod.getBehaviour().setBlockPlacePenalty(20.0);
	            mod.getClientBaritoneSettings().blockPlacementPenalty.value = 20.0;
	            mod.getClientBaritoneSettings().costHeuristic.value = 4.0;
            }

            Optional<Object> checkNewClosest = getClosestTo(mod, getOriginPos(mod));
            if (checkNewClosest.isPresent()) {
            	return super.onTick(mod);
            }

			if(mod.getClientBaritoneSettings().legitMine.value 
					&& _miningPos == null 
					&& _isOre
					)
			{
				OreDistribution oreDis = new OreDistribution(_blocks[0]);
				// int groundHeight = WorldHelper.getGroundHeight(mod, mod.getPlayer().getBlockX(), mod.getPlayer().getBlockZ());
				int groundHeight = 64;
				if(_searchTask instanceof GetToYTask 
					&& !(_searchTask.isActive() && !_searchTask.isFinished(mod)) 
					|| !(_searchTask instanceof GetToYTask))
				{
					if(groundHeight < oreDis.maxHeight
							&& mod.getPlayer().getY() > groundHeight)
					{
						Debug.logMessage("Going y ");
						_searchTask = new GetToYTask(oreDis.minHeight);
					}else
					if(
						groundHeight > oreDis.optimalHeight
						&& (mod.getPlayer().getY() < oreDis.minHeight - 20
						|| mod.getPlayer().getY() > oreDis.maxHeight + 20)
					) {
						Debug.logMessage("Going y 2");
						_searchTask = new GetToYTask(oreDis.optimalHeight);
					}
					
					else
					if(_searchTask instanceof TimeoutWanderTask && !_searchProgressChecker.check(mod))
					{
						if(groundHeight > oreDis.optimalHeight)
						{
							_searchTask = new BranchMiningTask(new BlockPos(
									mod.getPlayer().getBlockPos().getX(),
									oreDis.optimalHeight,
									mod.getPlayer().getBlockPos().getZ()
							), mod.getPlayer().getMovementDirection(), Arrays.asList(_blocks));
						}else
						{
							_searchTask = new BranchMiningTask(new BlockPos(
									mod.getPlayer().getBlockPos().getX(),
									oreDis.minHeight + 25,
									mod.getPlayer().getBlockPos().getZ()
							), mod.getPlayer().getMovementDirection(), Arrays.asList(_blocks));
						}
						
						
						_searchProgressChecker.reset();
					}else        
		            if( _searchTask == null 
		            || (_searchTask instanceof GetToYTask)  && !(_searchTask.isActive() && !_searchTask.isFinished(mod))
		            		)
		            {
		            	_searchTask = 
		            			new TimeoutWanderTask();
		            }
				}

	            


				if(groundHeight > oreDis.optimalHeight)
				{
					mod.getClientBaritoneSettings().exploreMaintainY.value = oreDis.optimalHeight == -59 ? oreDis.optimalHeight : oreDis.optimalHeight - 20;
				}else
				{
					mod.getClientBaritoneSettings().exploreMaintainY.value = oreDis.minHeight + 20;
				}

	            return _searchTask;
			} else {
				mod.getClientBaritoneSettings().exploreMaintainY.value = 100;
			}
           
			
            return super.onTick(mod);
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.reset();
                }
                _miningPos = newPos;
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity) {
                _miningPos = null;
                return _pickupTask;
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return mod.getBlockTracker().blockIsValid(b, _blocks) && WorldHelper.canBreak(mod, b);
            }
            if (obj instanceof ItemEntity drop) {
                Item item = drop.getStack().getItem();
                if (_targets != null) {
                    for (ItemTarget target : _targets) {
                        if (target.matches(item)) return true;
                    }
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart(AltoClef mod) {
            _progressChecker.reset();
            _miningPos = null;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            _desperateSettingsTimer.forceElapse();
        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        public boolean isMining() {
            return _miningPos != null;
        }

        public BlockPos miningPos() {
            return _miningPos;
        }
        
        protected static boolean isBlockVisible(BlockPos blockPos) {
            MinecraftClient mc = MinecraftClient.getInstance();
            Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
            Vec3d blockPosVec = new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
            HitResult hitResult = mc.world.raycast(new RaycastContext(playerPos, blockPosVec, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

            if (hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHitResult = (BlockHitResult) hitResult;
                return blockHitResult.getBlockPos().equals(blockPos);
            }
            return false;
        }
        
        protected static boolean isNextToAir(AltoClef mod, BlockPos pos) {
        	
        	return WorldHelper.isAir(mod, pos.up())
        			|| WorldHelper.isAir(mod, pos.down())
        			|| WorldHelper.isAir(mod, pos.east())
        			|| WorldHelper.isAir(mod, pos.west())
        			|| WorldHelper.isAir(mod, pos.north())
        			|| WorldHelper.isAir(mod, pos.south())
        			;
//        	return mod.getWorld().getBlockState(pos.up()).getBlock() == Blocks.CAVE_AIR
//        			|| mod.getWorld().getBlockState(pos.down()).getBlock() == Blocks.CAVE_AIR
//        			|| mod.getWorld().getBlockState(pos.east()).getBlock() == Blocks.CAVE_AIR
//        			|| mod.getWorld().getBlockState(pos.west()).getBlock() == Blocks.CAVE_AIR
//        			|| mod.getWorld().getBlockState(pos.north()).getBlock() == Blocks.CAVE_AIR
//        			|| mod.getWorld().getBlockState(pos.south()).getBlock() == Blocks.CAVE_AIR;	
        }
        
        
    }
    
}

class OreDistribution {
	
	public final int maxHeight;
	public final int optimalHeight;
	public final int minHeight;
	
	OreDistribution(Block block)
	{

    	if(block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE)
    	{
    		maxHeight = 192;
    		optimalHeight = 96;
    		minHeight = 0;
    	}else
    	if(block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE)
    	{
    		maxHeight = 112;
    		optimalHeight = 48;
    		minHeight = -16;
    	}else
    	if(block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE)
    	{
    		maxHeight = 72;
    		optimalHeight = 16;
    		minHeight = -32;
    	}else
    	if(block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE)
    	{
    		maxHeight = 64;
    		optimalHeight = 0;
    		minHeight = -59;
    	}else
    	if(block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE)
    	{
    		maxHeight = 32;
    		optimalHeight = -16;
    		minHeight = -59;
    	}else
    	if(block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)
    	{
    		maxHeight = 15;
    		optimalHeight = -59;
    		minHeight = -59;
    	}else
        if(block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE)
        {
    		maxHeight = 15;
    		optimalHeight = -59;
    		minHeight = -59;
		}else
		{
	    	maxHeight = 8;
			optimalHeight = 8;
			minHeight = 8;
		}
//    	throw new IllegalArgumentException("Unexpected value: " + block);
	}
}


