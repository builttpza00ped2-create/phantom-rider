package dev.phantomrider;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PhantomRiderMod implements ModInitializer {
    public static final String MOD_ID = "phantomrider";

    private static final double CRUISE_SPEED = 0.50;
    private static final double SPRINT_SPEED = 0.95;
    private static final double CLIMB_SPEED = 0.40;
    private static final double STRAFE_FACTOR = 0.60;
    private static final double ACCELERATION = 0.12;

    public static final AttachmentType<UUID> OWNER = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(MOD_ID, "owner"),
            builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .syncWith(UUIDUtil.STREAM_CODEC, AttachmentSyncPredicate.all()));

    public static final AttachmentType<Boolean> SADDLED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(MOD_ID, "saddled"),
            builder -> builder
                    .persistent(Codec.BOOL)
                    .syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all()));

    private static final Set<Mob> TAMED = new HashSet<>();
    private static final Map<UUID, UUID> RIDERS = new HashMap<>();
    private static final Map<UUID, Vec3> VELOCITY = new HashMap<>();

    private enum Action { NONE, TAME, SADDLE_ON, SADDLE_OFF, MOUNT }

    @Override
    public void onInitialize() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
            if (!isPhantom(entity) || !(entity instanceof Mob phantom)) return InteractionResult.PASS;

            Action action = decide(player, phantom, player.getItemInHand(hand));
            if (action == Action.NONE) return InteractionResult.PASS;

            if (level instanceof ServerLevel serverLevel) {
                perform(action, player, phantom, serverLevel);
            }
            return InteractionResult.SUCCESS;
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob && isTamedPhantom(mob)) TAMED.add(mob);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Mob mob) TAMED.remove(mob);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TAMED.clear();
            RIDERS.clear();
            VELOCITY.clear();
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (isPhantom(entity) && entity.hasAttached(OWNER) && source.is(DamageTypeTags.IS_FIRE)) {
                entity.clearFire();
                return false;
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (isPhantom(entity) && isSaddled(entity) && entity.level() instanceof ServerLevel serverLevel) {
                entity.spawnAtLocation(serverLevel, new ItemStack(Items.SADDLE));
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(PhantomRiderMod::tick);
    }

    private static Action decide(Player player, Mob phantom, ItemStack held) {
        UUID owner = phantom.getAttached(OWNER);
        boolean saddled = isSaddled(phantom);

        if (owner == null) {
            return (held.isEmpty() || held.is(Items.SADDLE)) ? Action.NONE : Action.TAME;
        }
        if (!owner.equals(player.getUUID())) return Action.NONE;

        if (held.is(Items.SADDLE)) return saddled ? Action.NONE : Action.SADDLE_ON;
        if (held.isEmpty() && saddled) return player.isShiftKeyDown() ? Action.SADDLE_OFF : Action.MOUNT;
        return Action.NONE;
    }

    private static void perform(Action action, Player player, Mob phantom, ServerLevel level) {
        switch (action) {
            case TAME -> {
                if (!player.hasInfiniteMaterials()) player.getItemInHand(InteractionHand.MAIN_HAND).shrink(1);
                phantom.setAttached(OWNER, player.getUUID());
                phantom.setAttached(SADDLED, false);
                phantom.setNoAi(true);
                phantom.setTarget(null);
                phantom.setPersistenceRequired();
                phantom.clearFire();
                TAMED.add(phantom);
                level.sendParticles(ParticleTypes.HEART,
                        phantom.getX(), phantom.getY() + 0.5, phantom.getZ(), 7, 0.4, 0.4, 0.4, 0.02);
            }
            case SADDLE_ON -> {
                if (!player.hasInfiniteMaterials()) player.getItemInHand(InteractionHand.MAIN_HAND).shrink(1);
                phantom.setAttached(SADDLED, true);
            }
            case SADDLE_OFF -> {
                phantom.setAttached(SADDLED, false);
                phantom.spawnAtLocation(level, new ItemStack(Items.SADDLE));
            }
            case MOUNT -> {
                if (!player.isPassenger()) player.startRiding(phantom);
            }
            default -> { }
        }
    }

    private static void tick(MinecraftServer server) {
        TAMED.removeIf(Entity::isRemoved);
        for (Mob phantom : TAMED) {
            if (phantom.isOnFire()) phantom.clearFire();
        }

        Set<UUID> active = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getVehicle() instanceof Mob phantom
                    && isPhantom(phantom)
                    && player.getUUID().equals(phantom.getAttached(OWNER))
                    && isSaddled(phantom)) {
                active.add(player.getUUID());
                RIDERS.put(player.getUUID(), phantom.getUUID());
                drive(player, phantom);
            }
        }

        Iterator<Map.Entry<UUID, UUID>> it = RIDERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            if (active.contains(entry.getKey())) continue;
            it.remove();
            VELOCITY.remove(entry.getValue());
            ServerPlayer rider = server.getPlayerList().getPlayer(entry.getKey());
            if (rider != null && rider.isAlive()) {
                rider.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false, true));
            }
        }
    }

    private static void drive(ServerPlayer player, Mob phantom) {
        Input input = player.getLastClientInput();

        float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
        Vec3 look = player.getLookAngle();
        Vec3 right = new Vec3(-Mth.cos(yawRad), 0.0, -Mth.sin(yawRad));

        double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double strafe = (input.right() ? 1 : 0) - (input.left() ? 1 : 0);

        Vec3 target = look.scale(forward).add(right.scale(strafe * STRAFE_FACTOR));
        if (target.lengthSqr() > 1.0E-4) {
            target = target.normalize().scale(input.sprint() ? SPRINT_SPEED : CRUISE_SPEED);
        } else {
            target = Vec3.ZERO;
        }
        if (input.jump()) target = target.add(0.0, CLIMB_SPEED, 0.0);

        Vec3 velocity = VELOCITY.getOrDefault(phantom.getUUID(), Vec3.ZERO);
        velocity = velocity.add(target.subtract(velocity).scale(ACCELERATION));

        phantom.setDeltaMovement(Vec3.ZERO);
        Vec3 before = phantom.position();
        phantom.move(MoverType.SELF, velocity);
        Vec3 moved = phantom.position().subtract(before);

        if (Math.abs(moved.x - velocity.x) > 1.0E-3) velocity = new Vec3(0.0, velocity.y, velocity.z);
        if (Math.abs(moved.y - velocity.y) > 1.0E-3) velocity = new Vec3(velocity.x, 0.0, velocity.z);
        if (Math.abs(moved.z - velocity.z) > 1.0E-3) velocity = new Vec3(velocity.x, velocity.y, 0.0);
        VELOCITY.put(phantom.getUUID(), velocity);
        phantom.setDeltaMovement(Vec3.ZERO);

        phantom.setYRot(player.getYRot());
        phantom.setXRot(player.getXRot() * 0.6F);
        phantom.setYBodyRot(player.getYRot());
        phantom.setYHeadRot(player.getYRot());
    }

    private static boolean isPhantom(Entity entity) {
        return entity.getType() == EntityType.PHANTOM;
    }

    private static boolean isTamedPhantom(Entity entity) {
        return isPhantom(entity) && entity.hasAttached(OWNER);
    }

    private static boolean isSaddled(Entity entity) {
        return Boolean.TRUE.equals(entity.getAttached(SADDLED));
    }
}
