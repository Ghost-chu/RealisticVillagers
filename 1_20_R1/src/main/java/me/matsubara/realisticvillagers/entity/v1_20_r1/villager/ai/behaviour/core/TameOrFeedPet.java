package me.matsubara.realisticvillagers.entity.v1_20_r1.villager.ai.behaviour.core;

import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import me.matsubara.realisticvillagers.data.ChangeItemType;
import me.matsubara.realisticvillagers.data.Exchangeable;
import me.matsubara.realisticvillagers.entity.IVillagerNPC;
import me.matsubara.realisticvillagers.entity.Pet;
import me.matsubara.realisticvillagers.entity.v1_20_r1.PetCat;
import me.matsubara.realisticvillagers.entity.v1_20_r1.PetWolf;
import me.matsubara.realisticvillagers.entity.v1_20_r1.villager.VillagerNPC;
import me.matsubara.realisticvillagers.event.VillagerTameEvent;
import me.matsubara.realisticvillagers.files.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftLivingEntity;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

@SuppressWarnings({"OptionalGetWithoutIsPresent", "ConstantConditions"})
public class TameOrFeedPet extends Behavior<Villager> implements Exchangeable {

    private @Getter ItemStack previousItem;
    private final int distanceToTame;
    private final int tameChance;
    private final float speedModifier;
    private final Predicate<LivingEntity> tameFilter;
    private final Set<Item> tameItems;
    private Item food;
    private boolean isTame;
    private int tryAgain;
    private boolean started;

    private static final int TRY_AGAIN_COOLDOWN = 1200;
    private static final BiPredicate<Villager, LivingEntity> ABANDONED = (villager, living) ->
            villager instanceof VillagerNPC npc
                    && living instanceof Pet pet
                    && pet.getOwnerUniqueId() != null
                    && pet.isTamedByVillager()
                    && Config.VILLAGER_ADOPTS_ABANDONED_PET.asBool()
                    // In this case, we don't need an updated offline; we just need to know if the villager is alive.
                    && npc.getPlugin().getTracker().getOfflineByUUID(pet.getOwnerUniqueId()) == null;

    public TameOrFeedPet(int distanceToTame, int tameChance, float speedModifier, Predicate<LivingEntity> tameFilter, Set<Item> tameItems) {
        super(ImmutableMap.of(
                MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT,
                VillagerNPC.HAS_TAMED_RECENTLY, MemoryStatus.VALUE_ABSENT));
        this.distanceToTame = distanceToTame;
        this.tameChance = tameChance;
        this.speedModifier = speedModifier;
        this.tameFilter = tameFilter;
        this.tameItems = tameItems;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (!started && tryAgain > 0) {
            tryAgain--;
            return false;
        }

        boolean canStart = villager instanceof VillagerNPC npc
                && npc.isDoingNothing(ChangeItemType.TAMING)
                && npc.checkCurrentActivity(Activity.IDLE)
                && !villager.getBrain().hasMemoryValue(VillagerNPC.HAS_TAMED_RECENTLY)
                && ((isTame = canTame(npc)) || canFeed(npc));

        if (!canStart) {
            // Try again in 15 seconds.
            tryAgain = TRY_AGAIN_COOLDOWN / 4;
        }
        return canStart;
    }

    private boolean canTame(VillagerNPC npc) {
        return getNearestUntamedOrAbandoned(npc).isPresent() && npc.getInventory().hasAnyOf(tameItems);
    }

    private boolean canFeed(VillagerNPC npc) {
        Optional<LivingEntity> nearestHungry = getNearestHungry(npc);
        return nearestHungry.isPresent()
                && nearestHungry.get() instanceof Animal animal
                && npc.getInventory().hasAnyMatching(animal::isFood);
    }

    @Override
    public boolean canStillUse(ServerLevel level, Villager villager, long game) {
        return checkExtraStartConditions(level, villager);
    }

    @Override
    public void start(ServerLevel level, Villager villager, long time) {
        if (villager instanceof VillagerNPC npc) {
            npc.setTaming(true);
            previousItem = npc.getMainHandItem();
        }

        started = true;

        LivingEntity tamable = get(villager);

        villager.getBrain().setMemory(MemoryModuleType.INTERACTION_TARGET, tamable);
        BehaviorUtils.lookAtEntity(villager, tamable);

        SimpleContainer inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isTame ? tameItems.contains(item.getItem()) : ((Animal) tamable).isFood(item)) {
                food = item.getItem();
                break;
            }
        }

        villager.setItemInHand(InteractionHand.MAIN_HAND, food.getDefaultInstance());
    }

    @Override
    public void stop(ServerLevel level, Villager villager, long time) {
        if (villager instanceof VillagerNPC npc) {
            npc.setTaming(false);
            npc.setItemSlot(EquipmentSlot.MAINHAND, previousItem);
        }

        started = false;
        tryAgain = 0;

        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    @Override
    public void tick(ServerLevel level, Villager villager, long time) {
        LivingEntity tamable = get(villager);
        BehaviorUtils.lookAtEntity(villager, tamable);

        if (!isWithinTamingDistance(villager, tamable)) {
            BehaviorUtils.setWalkAndLookTargetMemories(villager, tamable, speedModifier, 0);
            return;
        }

        if (tryAgain > 0) {
            tryAgain--;
            return;
        }

        boolean isWolf, isTamed = false;
        if (isTame && villager instanceof VillagerNPC npc && tamable instanceof Pet pet && (isTamed = canTame(level, npc, tamable))) {
            pet.tameByVillager(npc);
        } else if (!isTame && ((isWolf = tamable instanceof PetWolf) || tamable instanceof PetCat)) {
            if (isWolf) {
                tamable.heal(food.getFoodProperties().getNutrition(), EntityRegainHealthEvent.RegainReason.EATING);
            } else {
                tamable.playSound(SoundEvents.CAT_EAT, 1.0f, 1.0f);
                tamable.heal(food.getFoodProperties().getNutrition());
            }
            // Parrots can't be fed (only cookies, which will kill them).
        }

        if (isTame) {
            level.broadcastEntityEvent(tamable, (byte) (isTamed ? 7 : 6));
        }

        villager.getInventory().removeItemType(food, 1);

        if (!isTame || isTamed || !checkExtraStartConditions(level, villager)) {
            long cooldown = Config.TAME_COOLDOWN.asLong();
            if (cooldown > 0L) villager.getBrain().setMemoryWithExpiry(VillagerNPC.HAS_TAMED_RECENTLY, true, cooldown);
        } else {
            tryAgain += 10;
        }
    }

    private boolean canTame(ServerLevel level, IVillagerNPC npc, LivingEntity living) {
        if (!ABANDONED.test((Villager) npc, living) && level.random.nextInt(tameChance) != 0) return false;

        VillagerTameEvent event = new VillagerTameEvent((CraftLivingEntity) living.getBukkitEntity(), npc);
        Bukkit.getPluginManager().callEvent(event);

        return !event.isCancelled();
    }

    @Override
    protected boolean timedOut(long time) {
        return false;
    }

    private Optional<LivingEntity> getNearestUntamedOrAbandoned(@NotNull Villager villager) {
        Optional<NearestVisibleLivingEntities> nearest = villager.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        if (nearest.isEmpty()) return Optional.empty();

        return nearest.get().findClosest(tameFilter.or(living -> ABANDONED.test(villager, living)));
    }

    private Optional<LivingEntity> getNearestHungry(@NotNull Villager villager) {
        Optional<NearestVisibleLivingEntities> nearest = villager.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        if (nearest.isEmpty()) return Optional.empty();

        return nearest.get().findClosest(living -> living instanceof OwnableEntity ownable
                && villager.getUUID().equals(ownable.getOwnerUUID())
                && living instanceof Animal animal
                && animal.getHealth() < animal.getMaxHealth());
    }

    private boolean isWithinTamingDistance(@NotNull Villager villager, @NotNull LivingEntity tamable) {
        return villager.blockPosition().closerThan(tamable.blockPosition(), distanceToTame);
    }

    private @NotNull LivingEntity get(Villager villager) {
        return isTame ? getNearestUntamedOrAbandoned(villager).get() : getNearestHungry(villager).get();
    }
}