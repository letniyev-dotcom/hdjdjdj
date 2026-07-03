package com.letify.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letify.app.ui.components.ElasticOverscroll
import com.letify.app.ui.components.OverlayHost
import com.letify.app.ui.components.RoundedSlideOverlay
import com.letify.app.ui.components.overlayHostShiftFraction
import com.letify.app.ui.components.rememberParallaxProgress
import com.letify.app.ui.components.noFeedbackClick
import com.letify.app.ui.screens.AnketaRow
import com.letify.app.ui.screens.AnketaDetailScreen
import com.letify.app.ui.screens.ProfileScreen
import com.letify.app.ui.state.AnketaStatus
import com.letify.app.ui.state.AnketnicaState
import com.letify.app.ui.state.TransitionStyle
import com.letify.app.ui.theme.Letify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// One shared motion curve/duration for the sheet — same family as the overlay
// push (LetifyOverlay.PushEasing) so the whole app moves on one feel.
private val SheetEasing = CubicBezierEasing(0.32f, 0.72f, 0.0f, 1.0f)
private val SheetSpec = tween<Float>(340, easing = SheetEasing)

// Drag-release settle now rides a spring seeded with the finger's own velocity
// (see sheetOnDragEnd) instead of a fixed tween — a tween ignored the throw
// velocity and always spent its full 340ms easing curve decelerating to rest,
// and that slow tail was the "тянется как тугая резина / подлагивает" feel. The
// spring picks up the release velocity so a light flick glides the sheet
// open/closed immediately.
// Critically damped (dampingRatio = 1f) → NO overshoot/bounce at the end of the
// open. When it was underdamped (0.86) the spring sailed past the target and
// oscillated back — that end-of-open wobble was the "под конец открытия дрожит /
// дёргано" the user reported. It still picks up the finger's release velocity so
// a light flick glides it home.
private val SheetSettleSpring = spring<Float>(dampingRatio = 1f, stiffness = 300f)

// Commands funnelled through the single sheet-progress consumer (the
// LaunchedEffect in AnketnicaApp). Snap = follow the finger; Settle = animate to
// a resting 0/1 carrying the release velocity.
private sealed interface SheetCommand {
    data class Snap(val target: Float) : SheetCommand
    data class Settle(val target: Float, val velocity: Float) : SheetCommand
}

// The in-sheet push-in (RoundedSlideOverlay) takes ~360ms + 2 warm-up frames.
// The sheet only climbs to fullscreen AFTER that, so opening an anketa reads as
// two clean beats: (1) the detail slides in WITHIN the small resting sheet,
// then (2) the sheet expands to fullscreen — never both at once.
private const val PushInDelayMs = 380L

private val PeekDp = 96.dp

/**
 * Root shell. The bottom navbar is GONE — the home screen is the Profile, and
 * the applications list lives in a bottom sheet that peeks at the bottom of the
 * profile and pulls up to (almost) fullscreen. As it opens, the profile behind
 * it recedes (scale + dim + rounded corners), iOS-modal style.
 *
 * Layering (bottom → top):
 *   1. Receding layer: Profile + its pushed sub-screens (Сценарии, Оформление…)
 *   2. «Новые анкеты» sheet (peek ↔ expanded), only on the home profile
 *   3. Anketa detail — top overlay, slides in from the right
 */
@Composable
fun AnketnicaApp(state: AnketnicaState) {
    val subStack = remember { mutableStateListOf<SubRoute>() }
    var lastAction by remember { mutableStateOf("init") }
    val overlay: SubRoute? = subStack.lastOrNull()
    val underlay: SubRoute? = if (subStack.size >= 2) subStack[subStack.size - 2] else null
    val push: (SubRoute) -> Unit = { r -> subStack.add(r); lastAction = "push" }
    val pop: () -> Unit = { if (subStack.isNotEmpty()) subStack.removeAt(subStack.lastIndex); lastAction = "pop" }
    val overlayStateHolder = rememberSaveableStateHolder()

    val parallax = rememberParallaxProgress()
    val nestedParallax = remember(subStack.size, lastAction) {
        Animatable(if (lastAction == "pop") 0f else 1f)
    }

    // Anketa detail is a TOP overlay (over sheet + profile). Opened both from the
    // ankety sheet and from Profile → Списки.
    var detailId by remember { mutableStateOf<Int?>(null) }
    val openAnketa: (Int) -> Unit = { detailId = it }

    // Single driver for both the sheet translation and the profile receding.
    val sheetProgress = remember { Animatable(0f) }
    // Chrome: 1 on the home profile, 0 when a sub-screen covers it — lets the
    // sheet fade/slide away smoothly instead of popping in/out.
    val sheetChrome = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var rootHeightPx by remember { mutableStateOf(0f) }
    val topInsetDp: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInsetDp: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val peekPx = with(density) { (PeekDp + navInsetDp).toPx() }
    val topGapPx = with(density) { (topInsetDp + 12.dp).toPx() }
    val travelPx = (rootHeightPx - peekPx - topGapPx).coerceAtLeast(1f)
    val measured = rootHeightPx > 0f

    // Shared sheet-drive handlers: used by BOTH the sheet's own grabber AND a
    // vertical-drag gesture on the profile behind it. Dragging up on the profile
    // pulls «Новые анкеты» open; dragging down closes it — one continuous feel.
    // A SINGLE tracked job serialises every mutation of sheetProgress. Without
    // this, each drag event launched its own coroutine calling snapTo; on
    // release, onDragEnd's animateTo raced with a still-queued stale snapTo that
    // ran AFTER it through Animatable's mutator-mutex and cancelled the
    // animation mid-flight — that was the "лист дёргается / улетает / пропадает"
    // when flinging the sheet down. We cancel the previous mutation before every
    // new one so only the latest ever wins.
    // The sheet had two long-standing feel bugs, both fixed by funnelling EVERY
    // mutation of sheetProgress through ONE long-lived consumer coroutine fed by
    // a CONFLATED channel:
    //  1. "подлагивает" — the old onDrag launched (and cancelled) a brand-new
    //     coroutine calling snapTo on EVERY drag frame; that per-frame
    //     launch/cancel storm churned the mutator-mutex and micro-stuttered the
    //     sheet. Now onDrag just trySends the latest target and the consumer
    //     snaps to it — no per-frame coroutines.
    //  2. "тянется, потом возвращается / улетает" — the release animateTo used to
    //     race with still-queued drag snapTo calls through the mutator-mutex and
    //     got cancelled mid-flight. The consumer processes one command at a time
    //     and the channel is CONFLATED, so while a settle's animateTo suspends the
    //     loop any late Snap is simply overwritten in the 1-slot buffer and can
    //     never interrupt the settle.
    val sheetCommands = remember { Channel<SheetCommand>(Channel.CONFLATED) }
    var sheetSettling by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Clamp progress to [0,1] so a velocity-seeded settle can never overshoot
        // past the top and spring back down — a second guard against the
        // end-of-open jitter. With bounds set, the settle simply stops dead at
        // 0f / 1f even if the throw velocity would have carried it further.
        sheetProgress.updateBounds(0f, 1f)
        for (cmd in sheetCommands) {
            when (cmd) {
                is SheetCommand.Snap -> sheetProgress.snapTo(cmd.target)
                is SheetCommand.Settle -> {
                    sheetSettling = true
                    try {
                        sheetProgress.animateTo(
                            cmd.target,
                            SheetSettleSpring,
                            initialVelocity = cmd.velocity,
                        )
                    } finally {
                        sheetSettling = false
                    }
                }
            }
        }
    }
    val sheetOnDrag: (Float) -> Unit = { dyPx ->
        // Swallow drag deltas while a release settle animates — trailing fling
        // frames must not re-drive the sheet mid-settle.
        if (!sheetSettling) {
            // Travel is read LIVE from rootHeightPx: a lambda captured at first
            // composition otherwise pins travel at its 1px floor and flings
            // progress across 0..1 on the tiniest move.
            val tp = (rootHeightPx - peekPx - topGapPx).coerceAtLeast(1f)
            val next = (sheetProgress.value - dyPx / tp).coerceIn(0f, 1f)
            sheetCommands.trySend(SheetCommand.Snap(next))
        }
    }
    val sheetOnDragEnd: (Float) -> Unit = { vy ->
        // vy = the finger's throw velocity in px/s. A GENTLE flick now commits
        // (was ±700 px/s — that high bar is exactly why opening "требовал слишком
        // долгий свайп"); un-thrown, the sheet commits from just past 30% travel
        // instead of 40%. Lock out drags until the settle finishes so a trailing
        // fling frame can't reopen it.
        sheetSettling = true
        val tp = (rootHeightPx - peekPx - topGapPx).coerceAtLeast(1f)
        val target = when {
            vy < -320f -> 1f
            vy > 320f -> 0f
            else -> if (sheetProgress.value > 0.3f) 1f else 0f
        }
        // px/s → progress-units/s (progress grows as the finger moves UP, i.e. as
        // dy goes negative) so the spring continues the throw seamlessly.
        sheetCommands.trySend(SheetCommand.Settle(target, -vy / tp))
    }

    val sheetExpanded by remember { derivedStateOf { sheetProgress.value > 0.5f } }
    BackHandler(enabled = sheetExpanded && detailId == null && subStack.isEmpty()) {
        sheetCommands.trySend(SheetCommand.Settle(0f, 0f))
    }
    val onHome = subStack.isEmpty()
    LaunchedEffect(onHome) {
        sheetChrome.animateTo(if (onHome) 1f else 0f, SheetSpec)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { rootHeightPx = it.height.toFloat() },
    ) {
        // ── Receding layer ───────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = sheetProgress.value.coerceIn(0f, 1f)
                    val s = 1f - 0.06f * p
                    scaleX = s
                    scaleY = s
                    translationY = p * 14.dp.toPx()
                    if (p > 0.001f) {
                        shape = RoundedCornerShape(p * 22.dp.toPx())
                        clip = true
                    }
                }
                .background(Letify.colors.bg),
        ) {
            OverlayHost(parallaxProgress = parallax) {
                // On the home profile, a vertical drag anywhere drives the sheet
                // (up = open, down = close) instead of the old rubber-band
                // overscroll. detectVerticalDragGestures only claims the pointer
                // after the touch-slop, so taps on the pencil / settings rows are
                // unaffected. Disabled once a sub-screen is on top.
                val profileDrag = if (subStack.isEmpty()) {
                    Modifier.pointerInput(Unit) {
                        val vt = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { vt.resetTracking() },
                            onDragEnd = { sheetOnDragEnd(vt.calculateVelocity().y) },
                            onDragCancel = { sheetOnDragEnd(0f) },
                            onVerticalDrag = { change, dy ->
                                vt.addPosition(change.uptimeMillis, change.position)
                                change.consume(); sheetOnDrag(dy)
                            },
                        )
                    }
                } else Modifier
                Box(Modifier.fillMaxSize().then(profileDrag)) {
                    ProfileScreen(
                        state = state,
                        onEditProfile = { push(SubRoute.ProfileEdit) },
                        onScenarios = { push(SubRoute.Scenarios) },
                        onAppearance = { push(SubRoute.Appearance) },
                        onNotifications = { push(SubRoute.Notifications) },
                        onStats = { push(SubRoute.Stats) },
                        onLists = { push(SubRoute.Lists) },
                    )
                }
            }

            underlay?.let { u ->
                val style = state.transitionStyle
                val shiftFraction = overlayHostShiftFraction(style)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = nestedParallax.value.coerceIn(0f, 1f)
                            translationX = -(1f - p) * size.width * shiftFraction
                        }
                        .background(Letify.colors.bg),
                ) {
                    overlayStateHolder.SaveableStateProvider(u.stateKey()) {
                        RenderSubRoute(state = state, route = u, push = {}, dismiss = {}, openAnketa = openAnketa)
                    }
                    if (style == TransitionStyle.Cover) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = (1f - nestedParallax.value.coerceIn(0f, 1f)) * 0.16f }
                                .background(Color.Black),
                        )
                    }
                }
            }

            overlay?.let { current ->
                val animateInTop = lastAction != "pop"
                val topParallax = if (subStack.size >= 2) nestedParallax else parallax
                key(current, animateInTop) {
                    RoundedSlideOverlay(
                        parallaxProgress = topParallax,
                        onDismissed = { pop() },
                        animateIn = animateInTop,
                    ) { animatedBack ->
                        Box(Modifier.fillMaxSize().background(Letify.colors.bg)) {
                            overlayStateHolder.SaveableStateProvider(current.stateKey()) {
                                RenderSubRoute(
                                    state = state,
                                    route = current,
                                    push = { push(it) },
                                    dismiss = animatedBack,
                                    openAnketa = openAnketa,
                                )
                            }
                        }
                    }
                }
            }

            // Dim scrim over the receding layer — fades in with the sheet.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = sheetProgress.value.coerceIn(0f, 1f) * 0.5f }
                    .background(Color.Black),
            )
        }

        // ── Bottom sheet — always mounted; fades/slides away behind sub-screens. ─────
        NewAnketySheet(
            state = state,
            progress = sheetProgress,
            chrome = sheetChrome,
            travelPx = travelPx,
            topGapPx = topGapPx,
            peekPx = peekPx,
            visible = measured,
            interactive = subStack.isEmpty(),
            onOpenAnketa = openAnketa,
            onToggle = {
                val target = if (sheetProgress.value < 0.5f) 1f else 0f
                sheetCommands.trySend(SheetCommand.Settle(target, 0f))
            },
            onDrag = sheetOnDrag,
            onDragEnd = sheetOnDragEnd,
        )

        // ── Anketa detail — top-most overlay, slides in from the right. ───────
        detailId?.let { id ->
            val a = state.anketa(id)
            if (a == null) {
                detailId = null
            } else {
                val detailParallax = remember(id) { Animatable(1f) }
                key(id) {
                    RoundedSlideOverlay(
                        parallaxProgress = detailParallax,
                        onDismissed = { detailId = null },
                        animateIn = true,
                    ) { animatedBack ->
                        Box(Modifier.fillMaxSize().background(Letify.colors.bg)) {
                            AnketaDetailScreen(state, a, onBack = animatedBack)
                        }
                    }
                }
            }
        }

        Toast(state)
    }
}

/**
 * The «Новые анкеты» bottom sheet. Fills the screen and is translated DOWN so
 * only its header peeks; [progress] 0→1 pulls it up to just below the top inset.
 * A single [Animatable] drives its translation and (in the parent) the profile
 * receding, so they stay in perfect lockstep — no second animation, no lag.
 */
@Composable
private fun NewAnketySheet(
    state: AnketnicaState,
    progress: Animatable<Float, AnimationVector1D>,
    chrome: Animatable<Float, AnimationVector1D>,
    travelPx: Float,
    topGapPx: Float,
    peekPx: Float,
    visible: Boolean,
    interactive: Boolean,
    onOpenAnketa: (Int) -> Unit,
    onToggle: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    val newItems = state.ankety.filter { it.status == AnketaStatus.NEW }.sortedBy { it.agox }
    val expanded by remember { derivedStateOf { progress.value > 0.5f } }
    // Opening an anketa happens INSIDE the sheet (not a new top screen): a
    // nested OverlayHost + RoundedSlideOverlay pair on their OWN sheetPush
    // progress reproduce the app-wide push/«сдвиг» transition, contained within
    // the sheet bounds.
    var sheetDetailId by remember { mutableStateOf<Int?>(null) }
    val sheetPush = rememberParallaxProgress()

    // When an anketa is opened, the sheet climbs the last bit from its resting
    // position (just below the status bar) to the VERY top of the screen, so the
    // pushed-in detail gets the full height. The sheet KEEPS its rounded top
    // corners throughout — so the opened detail reads as a rounded iOS-style card
    // over the black backdrop (the user asked for rounded corners ONLY on the
    // opened screen, not a flat full-bleed rectangle).
    val sheetFull = remember { Animatable(0f) }
    LaunchedEffect(sheetDetailId != null) {
        if (sheetDetailId != null) {
            // Two beats: let the detail slide in within the small resting sheet
            // FIRST, THEN climb the sheet to fullscreen.
            delay(PushInDelayMs)
            sheetFull.animateTo(1f, SheetSpec)
        } else {
            // Closing: the detail has already pushed out to the right (via
            // animatedBack) before sheetDetailId cleared, so now just descend the
            // sheet back to its small resting height.
            sheetFull.animateTo(0f, SheetSpec)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value.coerceIn(0f, 1f)
                val c = chrome.value.coerceIn(0f, 1f)
                val f = sheetFull.value.coerceIn(0f, 1f)
                // f pulls the top gap to 0 → the sheet climbs the last bit to the
                // very top of the screen when a detail is open.
                translationY = topGapPx * (1f - f) + travelPx * (1f - p) + (1f - c) * (peekPx + 48.dp.toPx())
                alpha = if (visible) c else 0f
            }
            .clipToTopRounded()
            .background(Letify.colors.container),
    ) {

        // NestedScroll glue so the list scroll and the open/close sheet drag stop
        // fighting: an upward drag while the sheet isn't fully open pulls the sheet
        // up first (the list stays put); once the sheet is open and the list is
        // scrolled to the very top, a further downward drag collapses the sheet.
        val sheetNsc = remember(interactive) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // Only a USER DRAG (not fling momentum) moves the sheet here.
                    // The list's post-release fling keeps delivering scroll frames
                    // through nested-scroll; if those also drove the sheet they
                    // fought the settle animation and made it jitter/"улетать".
                    // Fling is handled cleanly in onPreFling/onPostFling → onDragEnd.
                    if (interactive && source == NestedScrollSource.Drag && available.y < 0f && progress.value < 1f) {
                        onDrag(available.y); return available
                    }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (interactive && source == NestedScrollSource.Drag && available.y > 0f && progress.value > 0f) {
                        onDrag(available.y); return available
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (interactive && available.y < 0f && progress.value < 1f) {
                        onDragEnd(available.y); return available
                    }
                    return Velocity.Zero
                }
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (interactive && available.y > 0f && progress.value > 0f) {
                        onDragEnd(available.y); return available
                    }
                    return Velocity.Zero
                }
            }
        }

        OverlayHost(parallaxProgress = sheetPush) {
        Column(Modifier.fillMaxSize()) {
            // Header = the peeking part. Tap toggles; vertical drag pulls it.
            val headerGestures = if (interactive) {
                Modifier
                    .noFeedbackClick { onToggle() }
                    .pointerInput(Unit) {
                        val vt = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { vt.resetTracking() },
                            onDragEnd = { onDragEnd(vt.calculateVelocity().y) },
                            onDragCancel = { onDragEnd(0f) },
                            onVerticalDrag = { change, dy ->
                                vt.addPosition(change.uptimeMillis, change.position)
                                change.consume(); onDrag(dy)
                            },
                        )
                    }
            } else Modifier
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(headerGestures),
            ) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(5.dp)
                            .clipToPill()
                            .background(Letify.colors.muted.copy(alpha = 0.5f)),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "Анкеты" else "Новые анкеты",
                        color = Letify.colors.text,
                        style = Letify.typography.headlineLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clipToPill()
                            .background(Letify.colors.accent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${newItems.size}", color = Color(0xFF0C1F12), style = Letify.typography.labelMedium)
                    }
                }
            }

            Box(Modifier.fillMaxSize().nestedScroll(sheetNsc)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    if (newItems.isEmpty()) {
                        item {
                            Text(
                                "Новых анкет нет.",
                                color = Letify.colors.muted,
                                style = Letify.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    } else {
                        items(newItems, key = { it.id }) { a ->
                            Box(Modifier.padding(horizontal = 10.dp)) {
                                AnketaRow(state, a) { sheetDetailId = a.id }
                            }
                        }
                    }
                }
            }
        }
        }

        // In-sheet anketa detail — slides in from the right OVER the list with the
        // same push transition, and the list host recedes left. Back / swipe-back
        // dismisses it back into the list, all within the sheet.
        sheetDetailId?.let { id ->
            val a = state.anketa(id)
            if (a == null) {
                sheetDetailId = null
            } else {
                key(id) {
                    RoundedSlideOverlay(
                        parallaxProgress = sheetPush,
                        onDismissed = { sheetDetailId = null },
                        animateIn = true,
                    ) { animatedBack ->
                        // Paint the in-sheet detail with the base background
                        // (NOT container): the cards inside are container-coloured,
                        // so a container backdrop merged everything into one flat
                        // slab (the "чёрный оверлей" / "контейнеры слились с фоном").
                        // Using bg — exactly like every other screen — gives the
                        // container cards their contrast back.
                        // Sheet is now fullscreen (under the status bar) while a
                        // detail is open, so the detail uses the REAL status-bar
                        // inset (default null) instead of the small 8dp override
                        // used when the sheet rested below the status bar.
                        Box(Modifier.fillMaxSize().background(Letify.colors.bg)) {
                            AnketaDetailScreen(state, a, onBack = animatedBack)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clipToTopRounded(): Modifier =
    this.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))

private fun Modifier.clipToPill(): Modifier =
    this.clip(RoundedCornerShape(999.dp))

@Composable
private fun Toast(state: AnketnicaState) {
    val msg = state.toastMsg
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(1800)
            state.toastMsg = null
        }
    }
    AnimatedVisibility(visible = msg != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 40.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier.padding(horizontal = 32.dp).clipToPill()
                    .background(Color(0xFF2A2A2E)).padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text(msg ?: "", color = Color.White, style = Letify.typography.bodyMedium)
            }
        }
    }
}