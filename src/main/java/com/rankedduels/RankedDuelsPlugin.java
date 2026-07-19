package com.rankedduels;

import com.google.inject.Provides;
import com.rankedduels.api.ApiClient;
import com.rankedduels.api.ChallengeInfo;
import com.rankedduels.ui.ChallengePromptOverlay;
import com.rankedduels.ui.DuelStatusOverlay;
import com.rankedduels.ui.RankedDuelsPanel;
import com.rankedduels.ui.FightBannerOverlay;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.WorldResult;

import java.awt.image.BufferedImage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.kit.KitType;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
    name = "Ranked Duels",
    description = "Challenge players to ranked duels anywhere. Tracks gear, world and results.",
    tags = {"pvp", "duel", "ranked", "ladder", "elo"}
)
public class RankedDuelsPlugin extends Plugin
{
    /** Bump on every release; the server can refuse older versions. */
    public static final String VERSION = "1.3.4";

    private static final String MENU_OPTION = "Ranked Duel";
    private static final int CHALLENGE_POLL_SECONDS = 5;
    private static final int FIGHT_START_TIMEOUT_SECONDS = 180; // duel must start within 3 min of accept

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private MenuManager menuManager;
    @Inject private OverlayManager overlayManager;
    @Inject private ScheduledExecutorService executor;
    @Inject private RankedDuelsConfig config;
    @Inject private ConfigManager configManager;
    @Inject private ApiClient api;
    @Inject private DuelStatusOverlay statusOverlay;
    @Inject private ChallengePromptOverlay promptOverlay;
    @Inject private FightBannerOverlay fightBanner;
    @Inject private RankedDuelsPanel sidePanel;
    @Inject private ClientToolbar clientToolbar;
    @Inject private WorldService worldService;
    @Inject private KeyManager keyManager;

    private NavigationButton navButton;

    private final HotkeyListener acceptHotkey = new HotkeyListener(() -> config.acceptKey())
    {
        @Override
        public void hotkeyPressed()
        {
            if (state == DuelState.CHALLENGE_RECEIVED)
            {
                acceptChallenge();
            }
        }
    };

    private final HotkeyListener declineHotkey = new HotkeyListener(() -> config.declineKey())
    {
        @Override
        public void hotkeyPressed()
        {
            if (state == DuelState.CHALLENGE_RECEIVED)
            {
                declineChallenge();
            }
            else if (state == DuelState.CHALLENGE_SENT || state == DuelState.PENDING_FIGHT)
            {
                cancelDuel();
            }
        }
    };

    @Getter private DuelState state = DuelState.IDLE;
    @Getter private String opponentName;
    @Getter private ChallengeInfo pendingChallenge; // incoming challenge awaiting our accept/decline
    @Getter private long activeDuelId = -1;
    @Getter private Instant acceptedAt;
    @Getter private Instant fightStartedAt;
    @Getter private double winDelta;   // projected rating change on win
    @Getter private double lossDelta;  // projected rating change on loss (negative)
    private Instant challengeSentAt;
    private static final int CHALLENGE_CLIENT_TIMEOUT_SECONDS = 125; // just past server TTL

    private int damageDealt;
    private int damageTaken;
    private ScheduledFuture<?> pollTask;
    private volatile String myName;      // local player name, set on the client thread
    private volatile int myWorldLocation = -1; // datacenter location code of the current world
    private boolean loginHandled;        // link/refresh done for this login

    // --- PK tracking (independent of ranked duels) ---
    private static final long PK_ATTRIBUTION_MS = 12_000;
    /** Players we recently damaged: name -> last hit time. */
    private final Map<String, Long> recentDamageDealt = new HashMap<>();
    /** Last player who attacked us, for death attribution. */
    private String lastPlayerAttacker;
    private long lastPlayerAttackTime;

    @Provides
    RankedDuelsConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RankedDuelsConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(statusOverlay);
        overlayManager.add(promptOverlay);
        overlayManager.add(fightBanner);

        menuManager.addPlayerMenuItem(MENU_OPTION);
        keyManager.registerKeyListener(acceptHotkey);
        keyManager.registerKeyListener(declineHotkey);
        promptOverlay.registerClicks();

        navButton = NavigationButton.builder()
            .tooltip("Ranked Duels")
            .icon(drawHitsplatIcon())
            .priority(6)
            .panel(sidePanel)
            .build();
        clientToolbar.addNavigation(navButton);
        sidePanel.setDuelStateSupplier(this::getState);
        sidePanel.setCancelAction(() -> clientThread.invoke(this::cancelDuel));

        pollTask = executor.scheduleAtFixedRate(this::pollChallenges, 5, CHALLENGE_POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** 24x24 hitsplat-style icon drawn at runtime - no bundled asset needed. */
    private static BufferedImage drawHitsplatIcon()
    {
        BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int cx = 12, cy = 12;
        int spikes = 11;
        java.awt.Polygon star = new java.awt.Polygon();
        for (int i = 0; i < spikes * 2; i++)
        {
            double angle = Math.PI * i / spikes - Math.PI / 2;
            double r = (i % 2 == 0) ? 11 : 7.2;
            star.addPoint((int) Math.round(cx + Math.cos(angle) * r), (int) Math.round(cy + Math.sin(angle) * r));
        }
        g.setColor(new java.awt.Color(0xC0, 0x28, 0x1C));
        g.fill(star);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 11f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.drawString("R", cx - fm.stringWidth("R") / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return img;
    }

    @Override
    protected void shutDown()
    {
        menuManager.removePlayerMenuItem(MENU_OPTION);
        keyManager.unregisterKeyListener(acceptHotkey);
        keyManager.unregisterKeyListener(declineHotkey);
        promptOverlay.unregisterClicks();
        overlayManager.remove(statusOverlay);
        overlayManager.remove(promptOverlay);
        overlayManager.remove(fightBanner);
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (pollTask != null) pollTask.cancel(true);
        reset();
    }

    // ------------------------------------------------------------------
    // 1) Right-click menu entry on other players
    //
    // MenuManager.addPlayerMenuItem adds "Ranked Duel" to every player's
    // right-click menu (the same mechanism the core Hiscore plugin uses
    // for its Lookup option). Clicks arrive as MenuOptionClicked with
    // type RUNELITE_PLAYER.
    // ------------------------------------------------------------------
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER
            || !MENU_OPTION.equals(event.getMenuOption()))
        {
            return;
        }
        if (state != DuelState.IDLE)
        {
            addGameMessage("Finish or cancel your current duel first.");
            return;
        }
        Player target = event.getMenuEntry().getPlayer();
        if (target == null || target == client.getLocalPlayer())
        {
            return;
        }
        sendChallenge(target);
    }

    // ------------------------------------------------------------------
    // 2) Challenge handshake through the server
    // ------------------------------------------------------------------
    private void sendChallenge(Player target)
    {
        if (!api.isLinked())
        {
            addGameMessage("Link your account first: open the Ranked Duels panel and enter your website code.");
            return;
        }
        if (api.isOutdated())
        {
            addGameMessage("Your Ranked Duels plugin is outdated. Restart RuneLite to get the update from the Plugin Hub.");
            return;
        }
        String targetName = sanitize(target.getName());
        setState(DuelState.CHALLENGE_SENT);
        challengeSentAt = Instant.now();
        opponentName = targetName;

        executor.execute(() -> {
            ApiClient.ChallengeResult result = api.sendChallenge(targetName, client.getWorld(), myWorldLocation);
            clientThread.invoke(() -> {
                if (result == null)
                {
                    addGameMessage("Could not send challenge. Is " + targetName + " registered on the ladder?");
                    reset();
                    return;
                }
                winDelta = result.winDelta;
                lossDelta = result.lossDelta;
                if (result.duelId > 0)
                {
                    // They had already challenged us - crossing challenges
                    // count as mutual agreement, the duel is live right now.
                    beginAcceptedDuel(result.duelId, targetName);
                }
                else
                {
                    addGameMessage("Ranked duel challenge sent to " + targetName
                        + " (win " + formatDelta(winDelta) + " / lose " + formatDelta(lossDelta)
                        + "). Waiting for them to accept...");
                }
            });
        });
    }

    /** Polled on a background thread; hits the server for challenge/duel state changes. */
    private void pollChallenges()
    {
        if (!api.isLinked() || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        try
        {
            ApiClient.PollResult result = api.poll(myName);
            if (result == null) return;

            clientThread.invoke(() -> handlePollResult(result));
        }
        catch (Exception e)
        {
            log.debug("Challenge poll failed", e);
        }
    }

    private boolean outdatedWarned;

    private void handlePollResult(ApiClient.PollResult result)
    {
        if (result.outdated && !outdatedWarned)
        {
            outdatedWarned = true;
            addGameMessage("A new version of Ranked Duels is required. Restart RuneLite to update from the Plugin Hub - duels are disabled until then.");
        }
        switch (state)
        {
            case IDLE:
                if (result.incomingChallenge != null)
                {
                    pendingChallenge = result.incomingChallenge;
                    setState(DuelState.CHALLENGE_RECEIVED);
                    addGameMessage(pendingChallenge.getChallengerName()
                        + " has challenged you to a Ranked Duel! Accept or decline in the overlay.");
                }
                break;
            case CHALLENGE_SENT:
                if (result.incomingChallenge != null && opponentName != null
                    && opponentName.equalsIgnoreCase(result.incomingChallenge.getChallengerName()))
                {
                    // We challenged them, they challenged us back before the
                    // server-side mutual merge could apply: accept theirs.
                    pendingChallenge = result.incomingChallenge;
                    acceptChallenge();
                    return;
                }
                if (result.duelAccepted)
                {
                    if (result.winDelta != 0 || result.lossDelta != 0)
                    {
                        winDelta = result.winDelta;
                        lossDelta = result.lossDelta;
                    }
                    beginAcceptedDuel(result.duelId, opponentName);
                }
                else if (result.duelDeclined)
                {
                    addGameMessage(opponentName + " declined the ranked duel.");
                    reset();
                }
                break;
            default:
                break;
        }
    }

    /** Called from the prompt overlay when the local player clicks Accept. */
    public void acceptChallenge()
    {
        if ((state != DuelState.CHALLENGE_RECEIVED && state != DuelState.CHALLENGE_SENT)
            || pendingChallenge == null) return;
        ChallengeInfo ch = pendingChallenge;
        winDelta = ch.getWinDelta();
        lossDelta = ch.getLossDelta();
        executor.execute(() -> {
            long duelId = api.respondToChallenge(ch.getChallengeId(), true, client.getWorld(), myWorldLocation);
            clientThread.invoke(() -> {
                if (duelId > 0)
                {
                    beginAcceptedDuel(duelId, ch.getChallengerName());
                }
                else
                {
                    addGameMessage("Could not accept the duel (world mismatch or challenge expired).");
                    reset();
                }
            });
        });
    }

    /** Called from the prompt overlay when the local player clicks Decline. */
    public void declineChallenge()
    {
        if (state != DuelState.CHALLENGE_RECEIVED || pendingChallenge == null) return;
        long id = pendingChallenge.getChallengeId();
        executor.execute(() -> api.respondToChallenge(id, false, client.getWorld(), myWorldLocation));
        reset();
    }

    private void beginAcceptedDuel(long duelId, String opponent)
    {
        activeDuelId = duelId;
        opponentName = opponent;
        pendingChallenge = null;
        acceptedAt = Instant.now();
        damageDealt = 0;
        damageTaken = 0;
        setState(DuelState.PENDING_FIGHT);
        addGameMessage("Ranked duel confirmed vs " + opponent + ". Fight anywhere on this world - first hit starts it. "
            + "Any outside damage voids the duel.");
    }

    // ------------------------------------------------------------------
    // 3) Fight tracking: first blood, third-party abort, damage totals
    // ------------------------------------------------------------------
    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        trackPkDamage(event);

        if (state != DuelState.PENDING_FIGHT && state != DuelState.FIGHTING)
        {
            return;
        }
        Actor target = event.getActor();
        Hitsplat hitsplat = event.getHitsplat();
        Player me = client.getLocalPlayer();
        Player opponent = findOpponent();

        boolean targetIsMe = target == me;
        boolean targetIsOpponent = opponent != null && target == opponent;
        if (!targetIsMe && !targetIsOpponent)
        {
            return; // damage elsewhere in the world, not our concern
        }

        boolean fromMe = hitsplat.isMine();
        boolean fromOpponent = isFromOpponent(target, hitsplat, opponent);

        // --- Third-party interference: someone outside the duel damaged a participant ---
        if (!fromMe && !fromOpponent && hitsplat.isOthers())
        {
            abortDuel("THIRD_PARTY_DAMAGE");
            return;
        }

        // --- Legitimate duel damage ---
        if (targetIsOpponent && fromMe)
        {
            onDuelDamage();
            damageDealt += hitsplat.getAmount();
        }
        else if (targetIsMe && fromOpponent)
        {
            onDuelDamage();
            damageTaken += hitsplat.getAmount();
        }
    }

    private boolean isFromOpponent(Actor target, Hitsplat hitsplat, Player opponent)
    {
        if (opponent == null) return false;
        // If I'm the target and the hitsplat isn't mine, check who is interacting with me.
        if (target == client.getLocalPlayer() && !hitsplat.isMine())
        {
            Actor interacting = opponent.getInteracting();
            return interacting == client.getLocalPlayer();
        }
        return false;
    }

    private void onDuelDamage()
    {
        if (state == DuelState.PENDING_FIGHT)
        {
            fightStartedAt = Instant.now();
            setState(DuelState.FIGHTING);
            final Map<String, Integer> myGear = snapshotOwnGear();
            final Map<String, Integer> oppGear = snapshotOpponentGear();
            final int world = client.getWorld();
            executor.execute(() -> api.reportFightStarted(activeDuelId, world, myGear, oppGear));
            addGameMessage("Ranked duel started vs " + opponentName + "!");
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        Actor dead = event.getActor();
        Player me = client.getLocalPlayer();

        // --- PK tracking: every player kill/death counts, duel or not ---
        if (dead instanceof Player && api.isLinked())
        {
            long now = System.currentTimeMillis();
            if (dead == me)
            {
                if (lastPlayerAttacker != null && now - lastPlayerAttackTime <= PK_ATTRIBUTION_MS)
                {
                    executor.execute(() -> api.reportPk("death", client.getWorld()));
                    lastPlayerAttacker = null;
                }
            }
            else
            {
                String deadName = sanitize(dead.getName());
                Long lastHit = deadName != null ? recentDamageDealt.get(deadName) : null;
                if (lastHit != null && now - lastHit <= PK_ATTRIBUTION_MS)
                {
                    executor.execute(() -> api.reportPk("kill", client.getWorld()));
                    recentDamageDealt.remove(deadName);
                }
            }
        }

        // --- Ranked duel resolution ---
        if (state != DuelState.FIGHTING)
        {
            return;
        }
        Player opponent = findOpponent();
        if (dead == me)
        {
            finishDuel(false);
        }
        else if (opponent != null && dead == opponent)
        {
            finishDuel(true);
        }
    }

    /** Feed the PK attribution maps from every hitsplat we can see. */
    private void trackPkDamage(HitsplatApplied event)
    {
        Actor target = event.getActor();
        Hitsplat hitsplat = event.getHitsplat();
        Player me = client.getLocalPlayer();
        if (me == null || !(target instanceof Player))
        {
            return;
        }
        long now = System.currentTimeMillis();

        if (target != me && hitsplat.isMine() && hitsplat.getAmount() > 0)
        {
            String name = sanitize(target.getName());
            if (name != null)
            {
                recentDamageDealt.put(name, now);
                if (recentDamageDealt.size() > 32)
                {
                    recentDamageDealt.values().removeIf(t -> now - t > PK_ATTRIBUTION_MS);
                }
            }
        }
        else if (target == me && hitsplat.isOthers() && hitsplat.getAmount() > 0)
        {
            // Someone hit us; remember the most likely player attacker.
            Actor interacting = me.getInteracting();
            if (interacting instanceof Player)
            {
                lastPlayerAttacker = sanitize(interacting.getName());
                lastPlayerAttackTime = now;
            }
            else
            {
                // Fall back: any player currently targeting us.
                for (Player p : client.getPlayers())
                {
                    if (p != null && p != me && p.getInteracting() == me)
                    {
                        lastPlayerAttacker = sanitize(p.getName());
                        lastPlayerAttackTime = now;
                        break;
                    }
                }
            }
        }
    }

    @Subscribe
    public void onPlayerDespawned(PlayerDespawned event)
    {
        if (state != DuelState.FIGHTING)
        {
            return;
        }
        Player opponent = findOpponent();
        if (opponent != null && event.getPlayer() == opponent)
        {
            // Opponent vanished mid-fight without dying: teleport/logout = forfeit report.
            // The server cross-checks with their client's report before deciding.
            reportEscape();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Defer linking to GameTick: the local player's name is often
            // not available yet at the moment the state flips to LOGGED_IN.
            loginHandled = false;
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
        {
            myName = null;
            api.clearIdentity();
        }

    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Keep the local player name + account identity current (read on
        // the client thread, consumed by background API calls).
        Player local = client.getLocalPlayer();
        if (local != null && local.getName() != null)
        {
            myName = sanitize(local.getName());
        }
        api.updateIdentity(client.getAccountHash(), client.getUsername());

        // Resolve the current world's datacenter location code (RuneLite's
        // world list knows it authoritatively). Cheap: cached WorldResult.
        try
        {
            WorldResult worlds = worldService.getWorlds();
            if (worlds != null)
            {
                net.runelite.http.api.worlds.World w = worlds.findWorld(client.getWorld());
                if (w != null)
                {
                    myWorldLocation = w.getLocation();
                }
            }
        }
        catch (Exception ignored)
        {
            // world list unavailable: server falls back to its own mapping
        }

        // Handle once per login, as soon as we actually know our name.
        if (!loginHandled && myName != null)
        {
            loginHandled = true;
            final String name = myName;
            final boolean hasCode = !config.linkCode().isEmpty();

            if (!api.isLinked())
            {
                if (hasCode)
                {
                    // Player entered a website link code: claim/merge flow.
                    executor.execute(() -> {
                        int linkResult = api.linkAccount(name);
                        clientThread.invoke(() -> addGameMessage(linkResult == ApiClient.LINK_OK
                            ? "Website account linked! You're on the ladder as " + name + "."
                            : "Account linking failed - check your link code in the plugin settings."));
                        if (linkResult != ApiClient.LINK_FAILED)
                        {
                            clearLinkCode(); // used or invalid: either way it's spent
                        }
                        if (linkResult == ApiClient.LINK_OK)
                        {
                            sidePanel.refreshAsync();
                        }
                    });
                }
                else
                {
                    // Zero-setup onboarding: register automatically.
                    executor.execute(() -> {
                        boolean registered = api.autoRegister(name);
                        clientThread.invoke(() -> addGameMessage(registered
                            ? "Welcome to Ranked Duels! You're on the ladder as " + name
                                + " - right-click any player to challenge them. "
                                + "Optional: create an account at rankedduel.com to manage your profile."
                            : "Could not reach the Ranked Duels server. Will retry on your next login."));
                        if (registered)
                        {
                            sidePanel.refreshAsync();
                        }
                    });
                }
            }
            else if (hasCode)
            {
                // Already on the ladder (auto-registered) and the player has
                // now entered a code: claim the existing profile - rating and
                // history merge into their website account.
                executor.execute(() -> {
                    int linkResult = api.linkAccount(name);
                    clientThread.invoke(() -> addGameMessage(linkResult == ApiClient.LINK_OK
                        ? "Profile claimed! Your rating and match history are now attached to your rankedduel.com account."
                        : (linkResult == ApiClient.LINK_INVALID_CODE
                            ? "That link code was already used - removed it from your settings."
                            : "Claiming failed - could not reach the server.")));
                    if (linkResult != ApiClient.LINK_FAILED)
                    {
                        clearLinkCode();
                    }
                    if (linkResult == ApiClient.LINK_OK)
                    {
                        sidePanel.refreshAsync();
                    }
                });
            }
            else
            {
                sidePanel.refreshAsync();
            }
        }

        // Timeout: accepted but never started fighting
        if (state == DuelState.PENDING_FIGHT && acceptedAt != null
            && Instant.now().isAfter(acceptedAt.plusSeconds(FIGHT_START_TIMEOUT_SECONDS)))
        {
            abortDuel("TIMEOUT");
        }

        // Timeout: challenge sent but never answered - don't stay stuck.
        if (state == DuelState.CHALLENGE_SENT && challengeSentAt != null
            && Instant.now().isAfter(challengeSentAt.plusSeconds(CHALLENGE_CLIENT_TIMEOUT_SECONDS)))
        {
            addGameMessage(opponentName + " didn't answer the challenge - it expired.");
            executor.execute(() -> api.cancelChallenge());
            reset();
        }
    }

    // ------------------------------------------------------------------
    // 4) Reporting results
    // ------------------------------------------------------------------
    private void finishDuel(boolean iWon)
    {
        final Map<String, Integer> myGear = snapshotOwnGear();
        final Map<String, Integer> oppGear = snapshotOpponentGear();
        final long duelId = activeDuelId;
        final int world = client.getWorld();
        final int dealt = damageDealt;
        final int taken = damageTaken;
        final String opponent = opponentName;

        executor.execute(() -> {
            api.reportFightFinished(duelId, iWon, world, myGear, oppGear, dealt, taken);
            executor.schedule(sidePanel::refreshAsync, 3, TimeUnit.SECONDS);
        });
        addGameMessage(iWon
            ? "Victory! Ranked duel result submitted."
            : "Defeat. Ranked duel result submitted.");
        reset();
    }

    private void abortDuel(String reason)
    {
        final long duelId = activeDuelId;
        executor.execute(() -> api.reportAbort(duelId, reason));
        addGameMessage("Ranked duel voided: " + humanReason(reason));
        reset();
    }

    private void reportEscape()
    {
        final long duelId = activeDuelId;
        executor.execute(() -> api.reportAbort(duelId, "OPPONENT_ESCAPED"));
        addGameMessage("Opponent left the fight. Report sent - the server will resolve it.");
        reset();
    }

    /** Cancel before the fight starts (hotkey or side-panel button). */
    public void cancelDuel()
    {
        if (state == DuelState.CHALLENGE_SENT)
        {
            executor.execute(() -> api.cancelChallenge());
            addGameMessage("Challenge cancelled.");
            reset();
        }
        else if (state == DuelState.PENDING_FIGHT)
        {
            abortDuel("CANCELLED");
        }
    }

    // ------------------------------------------------------------------
    // Gear snapshots
    // ------------------------------------------------------------------
    private Map<String, Integer> snapshotOwnGear()
    {
        Map<String, Integer> gear = new HashMap<>();
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment != null)
        {
            for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
            {
                Item item = equipment.getItem(slot.getSlotIdx());
                if (item != null && item.getId() > 0)
                {
                    gear.put(slot.name(), item.getId());
                }
            }
        }
        return gear;
    }

    private Map<String, Integer> snapshotOpponentGear()
    {
        Map<String, Integer> gear = new HashMap<>();
        Player opponent = findOpponent();
        if (opponent == null || opponent.getPlayerComposition() == null)
        {
            return gear;
        }
        PlayerComposition comp = opponent.getPlayerComposition();
        for (KitType kit : KitType.values())
        {
            int itemId = comp.getEquipmentId(kit);
            if (itemId > 0)
            {
                gear.put(kit.name(), itemId);
            }
        }
        return gear;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private Player findOpponent()
    {
        if (opponentName == null) return null;
        for (Player p : client.getPlayers())
        {
            if (p != null && opponentName.equals(sanitize(p.getName())))
            {
                return p;
            }
        }
        return null;
    }

    private void setState(DuelState newState)
    {
        log.debug("Duel state {} -> {}", state, newState);
        state = newState;
        sidePanel.refreshAsync(); // keeps the cancel button in sync
    }

    private void reset()
    {
        state = DuelState.IDLE;
        opponentName = null;
        pendingChallenge = null;
        activeDuelId = -1;
        acceptedAt = null;
        fightStartedAt = null;
        damageDealt = 0;
        damageTaken = 0;
        winDelta = 0;
        lossDelta = 0;
        challengeSentAt = null;
        sidePanel.refreshAsync();
    }

    /** "+12.4" / "-15.1" for chat and overlays. */
    public static String formatDelta(double delta)
    {
        return (delta >= 0 ? "+" : "") + String.format("%.1f", delta);
    }

    /** One-time use: wipe the code from settings after a successful claim. */
    private void clearLinkCode()
    {
        configManager.setConfiguration("rankedduels", "linkCode", "");
    }

    /**
     * Safe from any thread and any game state: hops to the client thread
     * and only touches the chat when a local player actually exists.
     */
    private void addGameMessage(String message)
    {
        clientThread.invoke(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null)
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "<col=b30000>[Ranked Duels]</col> " + message, null);
            }
            else
            {
                log.info("[Ranked Duels] {}", message);
            }
        });
    }

    private static String sanitize(String name)
    {
        return name == null ? null : name.replace('\u00A0', ' ').trim();
    }

    private static String humanReason(String reason)
    {
        switch (reason)
        {
            case "THIRD_PARTY_DAMAGE": return "a third player interfered.";
            case "TIMEOUT": return "the fight never started in time.";
            case "CANCELLED": return "cancelled by a participant.";
            default: return reason.toLowerCase() + ".";
        }
    }
}
