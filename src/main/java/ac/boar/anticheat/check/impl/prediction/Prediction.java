package ac.boar.anticheat.check.impl.prediction;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.check.api.Check;
import ac.boar.anticheat.check.api.annotations.CheckInfo;
import ac.boar.anticheat.check.api.impl.OffsetHandlerCheck;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.prediction.engine.data.VectorType;
import ac.boar.anticheat.util.BoarLog;
import ac.boar.anticheat.util.MathUtil;
import ac.boar.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

import java.util.HashMap;
import java.util.Map;

@CheckInfo(name = "Prediction")
public class Prediction extends OffsetHandlerCheck {
    private final Map<String, Check> checks = new HashMap<>();

    public Prediction(BoarPlayer player) {
        super(player);

        this.checks.put("Phase", new Check(player, "Phase", "", false));
        this.checks.put("Velocity", new Check(player, "Velocity", "", false));

        this.checks.put("Strafe", new Check(player, "Strafe", "", false));
        this.checks.put("Speed", new Check(player, "Speed", "", false));
        this.checks.put("Flight", new Check(player, "Flight", "", false));

        this.checks.put("Collisions", new Check(player, "Collisions", "", false));
    }

    @Override
    public void onPredictionComplete(float offset) {
        if (player.tick < 10 || offset < player.getMaxOffset()) {
            return;
        }

        boolean canFail = shouldDoFail();
        if (!canFail || offset < Boar.getConfig().alertThreshold()) {
            logAndRewind(offset, canFail, "silent");
            return;
        }

        logAndRewind(offset, true, "fail");

        boolean claimedHorizontal = player.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION);
        boolean claimedVertical = player.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION);
        if (claimedVertical != player.verticalCollision || claimedHorizontal != player.horizontalCollision) {
            fail("Phase", "o: " + offset + ", expect: (" + player.horizontalCollision + "," + player.verticalCollision + "), actual: (" + claimedHorizontal + "," + claimedVertical + ")");
        }

        if (player.bestPossibility.getType() == VectorType.VELOCITY) {
            fail("Velocity", "o: " + offset);
            return;
        }

        if (player.unvalidatedTickEnd.distanceTo(player.velocity) < player.getMaxOffset()) {
            fail("Collisions", "o: " + offset);
        }

        Vec3 actual = player.unvalidatedPosition.subtract(player.prevUnvalidatedPosition);
        Vec3 predicted = player.position.subtract(player.prevUnvalidatedPosition);
        if (!MathUtil.sameDirectionHorizontal(actual, predicted)) {
            fail("Strafe", "o: " + offset + ", expected direction: " + MathUtil.signAll(predicted).horizontalToString() + ", actual direction: " + MathUtil.signAll(actual).horizontalToString());
        }

        float squaredActual = actual.horizontalLengthSquared(), squaredPredicted = predicted.horizontalLengthSquared();
        if (actual.horizontalLengthSquared() > predicted.horizontalLengthSquared()) {
            fail("Speed", "o: " + offset + ", expected: " + squaredPredicted + ", actual: " + squaredActual);
        }

        if (Math.abs(player.position.y - player.unvalidatedPosition.y) > player.getMaxOffset()) {
            fail("Flight", "o: " + offset);
        }
    }

    public boolean shouldDoFail() {
        return player.tickSinceBlockResync <= 0 && !player.insideUnloadedChunk && !player.getTeleportUtil().isTeleporting() && player.sinceLoadingScreen > 5 && player.compensatedWorld.isChunkLoaded((int) player.position.x, (int) player.position.z);
    }

    private void logAndRewind(float offset, boolean canFail, String reason) {
        logPredictionSetback(offset, canFail, reason);
        player.getTeleportUtil().rewind(player.tick);
    }

    private void logPredictionSetback(float offset, boolean canFail, String reason) {
        boolean claimedHorizontal = player.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION);
        boolean claimedVertical = player.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION);
        Vec3 actualMove = player.unvalidatedPosition.subtract(player.prevUnvalidatedPosition);
        Vec3 predictedMove = player.position.subtract(player.prevUnvalidatedPosition);

        BoarLog.debug("prediction", "Movement discrepancy for " + player.getSession().bedrockUsername()
                + ", protocol=" + player.getSession().protocolVersion()
                + ", action=rewind"
                + ", reason=" + reason
                + ", tick=" + player.tick
                + ", offset=" + offset
                + ", maxOffset=" + player.getMaxOffset()
                + ", alertThreshold=" + Boar.getConfig().alertThreshold()
                + ", canFail=" + canFail
                + ", predictedPos=" + player.position
                + ", actualPos=" + player.unvalidatedPosition
                + ", predictedMove=" + predictedMove
                + ", actualMove=" + actualMove
                + ", velocity=" + player.velocity
                + ", clientDelta=" + player.unvalidatedTickEnd
                + ", bestType=" + player.bestPossibility.getType()
                + ", collisions(serverH=" + player.horizontalCollision
                + ", serverV=" + player.verticalCollision
                + ", clientH=" + claimedHorizontal
                + ", clientV=" + claimedVertical + ")"
                + ", state(onGround=" + player.onGround
                + ", water=" + player.touchingWater
                + ", unloadedChunk=" + player.insideUnloadedChunk
                + ", loading=" + player.inLoadingScreen
                + ", sinceLoading=" + player.sinceLoadingScreen
                + ", teleporting=" + player.getTeleportUtil().isTeleporting()
                + ", blockResync=" + player.tickSinceBlockResync + ")"
                + ", input=" + player.getInputData());
    }

    public void fail(String name, String verbose) {
        if (Boar.getConfig().disabledChecks().contains(name)) {
            return;
        }

        this.checks.get(name).fail(verbose);
    }
}
