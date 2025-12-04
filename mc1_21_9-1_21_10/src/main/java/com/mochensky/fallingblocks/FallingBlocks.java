package com.mochensky.fallingblocks;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.*;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class FallingBlocks implements ModInitializer {

    private static final int MIN_RADIUS = 9;
    private static final int MAX_RADIUS = 15;

    private static final long INTERVAL_MIN_TICKS = 20L * 30L;
    private static final long INTERVAL_MAX_TICKS = 20L * 60L * 2L;

    private static final long WARNING_TICKS = 20 * 5;

    private ServerBossBar globalBossBar;
    private long nextFallTime = 0;

    @Override
    public void onInitialize() {
        globalBossBar = new ServerBossBar(Text.literal("N/A"), BossBar.Color.WHITE, BossBar.Style.PROGRESS);
        globalBossBar.setPercent(1.0f);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> resetModState());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getPlayerManager().getCurrentPlayerCount() == 0) {
                return;
            }

            long time = server.getOverworld().getTime();

            if (nextFallTime == 0) {
                nextFallTime = time + getRandomIntervalTicks();
            }

            updateBossBar(server);

            if (time >= nextFallTime) {
                triggerFallingBlocks(server);
                nextFallTime = time + getRandomIntervalTicks();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (nextFallTime > 0) {
                long time = server.getOverworld().getTime();
                long remaining = nextFallTime - time;
                if (remaining <= WARNING_TICKS) {
                    globalBossBar.addPlayer(handler.getPlayer());
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> globalBossBar.removePlayer(handler.getPlayer()));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> resetModState());
    }

    private void resetModState() {
        nextFallTime = 0;
        globalBossBar.setPercent(1.0f);
        globalBossBar.setVisible(false);
        globalBossBar.clearPlayers();
    }

    private long getRandomIntervalTicks() {
        if (INTERVAL_MAX_TICKS <= INTERVAL_MIN_TICKS) {
            return INTERVAL_MIN_TICKS;
        }
        return ThreadLocalRandom.current().nextLong(INTERVAL_MIN_TICKS, INTERVAL_MAX_TICKS + 1L);
    }

    private int getRandomRadius() {
        if (MAX_RADIUS <= MIN_RADIUS) {
            return MIN_RADIUS;
        }
        return ThreadLocalRandom.current().nextInt(MIN_RADIUS, MAX_RADIUS + 1);
    }

    private void updateBossBar(MinecraftServer server) {
        long time = server.getOverworld().getTime();
        long remaining = nextFallTime - time;

        if (remaining <= WARNING_TICKS) {
            globalBossBar.setVisible(true);

            float progress = (float) remaining / WARNING_TICKS;
            progress = MathHelper.clamp(progress, 0.0f, 1.0f);

            int secondsLeft = (int) ((remaining + 19) / 20);

            globalBossBar.setName(Text.literal(String.valueOf(secondsLeft)));
            globalBossBar.setPercent(progress);

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!globalBossBar.getPlayers().contains(player)) {
                    globalBossBar.addPlayer(player);
                }
            }
        } else {
            globalBossBar.setVisible(false);
        }
    }

    private void triggerFallingBlocks(MinecraftServer server) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return;

        Set<BlockPos> processed = new HashSet<>();
        int radius = getRandomRadius();

        for (ServerPlayerEntity player : players) {
            ServerWorld world = player.getEntityWorld();
            Vec3d pos = player.getEntityPos();
            BlockPos center = BlockPos.ofFloored(pos.x, pos.y, pos.z);
            BlockPos.Mutable mutable = new BlockPos.Mutable();

            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x * x + y * y + z * z > radius * radius + 0.5) continue;

                        mutable.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                        BlockPos immutablePos = mutable.toImmutable();
                        if (processed.contains(immutablePos)) continue;

                        BlockState state = world.getBlockState(mutable);
                        Block block = state.getBlock();

                        if (canFall(state, block, world, mutable)) {
                            processed.add(immutablePos);
                            world.removeBlock(mutable, false);
                            FallingBlockEntity.spawnFromBlock(world, mutable, state);
                        }
                    }
                }
            }
        }
    }

    private boolean canFall(BlockState state, Block block, World world, BlockPos pos) {
        if (state.isAir()) return false;
        if (block instanceof FluidBlock) return false;

        if (block == Blocks.BEDROCK ||
                block == Blocks.NETHER_PORTAL ||
                block == Blocks.END_PORTAL ||
                block == Blocks.END_GATEWAY ||
                block == Blocks.END_PORTAL_FRAME ||
                block instanceof PlantBlock)

            return false;


        if (block == Blocks.OBSIDIAN) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacentPos = pos.offset(direction);
                BlockState adjacentState = world.getBlockState(adjacentPos);
                if (adjacentState.isOf(Blocks.NETHER_PORTAL)) {
                    return false;
                }
            }
        }

        if (block instanceof SnowBlock && state.get(SnowBlock.LAYERS) < 8) return false;

        return !world.getBlockState(pos.down()).isFullCube(world, pos.down());
    }
}