package com.dazix.caelis.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dazix.caelis.R

/*
 * The shared building blocks. Every screen is a scrolling column of cards under a flexible app bar,
 * so these keep spacing, shape, disabled-state colouring and the "why is this off" explanations
 * consistent — and keep the accessibility rules (48dp targets, a description on every icon-only
 * control, never colour alone) in one place instead of on every call site.
 */

/**
 * One screen: a two-row top app bar that collapses to a single row as the content scrolls, the app's
 * navigation (bottom bar on a phone, rail on anything wider — supplied by [LocalNavigationBar]), the
 * scrolling content, and an optional floating layer pinned above the navigation for the controls that
 * must stay reachable while a session runs.
 *
 * [MediumFlexibleTopAppBar] is the expressive two-row bar: it sizes itself from its content instead of
 * from a fixed token height, which is what lets the title take two lines at a large font scale, and it
 * has the subtitle slot the screens use for the one line of context under the title.
 *
 * Nothing here has a fixed height, so the whole screen survives a large font scale and landscape.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    floating: @Composable (BoxScope.() -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val navigation = LocalNavigationBar.current
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    // Two lines in the expanded row, ellipsised rather than clipped once the font
                    // scale outgrows the app bar Material gives it.
                    Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                subtitle = subtitle?.let {
                    {
                        Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = { trailing?.invoke() },
                colors = TopAppBarDefaults.topAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (onBack == null) {
                navigation?.invoke()
            }
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // Capped and centred: one column of cards stretched across an unfolded fold or a
                    // tablet gives 36sp figures a metre of whitespace and lines too long to scan.
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                // Room for the floating layer, so the last card is never hidden behind it. Derived from
                // the toolbar's own container size rather than hand-tuned, so it follows the component.
                Spacer(
                    Modifier.height(
                        if (floating != null) {
                            FloatingLayerClearance
                        } else {
                            24.dp
                        }
                    )
                )
            }
            floating?.invoke(this)
        }
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    tone: CardTone = CardTone.NEUTRAL,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = when (tone) {
        CardTone.NEUTRAL -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        CardTone.WARNING -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        CardTone.ACCENT -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
            content()
        }
    }
}

enum class CardTone { NEUTRAL, WARNING, ACCENT }

/** A switch with its explanation, plus a visible reason when it cannot be switched at all. */
@Composable
fun SwitchRow(
    /** Null when the card's own title already names the switch; the description then labels it. */
    title: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    /** Shows the disabled reason as an [InfoNote], for a reason that is a hint rather than a fault. */
    disabledReasonAsInfo: Boolean = false,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        // The label is part of the control, not a caption next to it: a 48dp-tall row whose only
        // touchable pixel is the switch itself is a miss for anyone who does not aim precisely, and it
        // reads to a screen reader as a label and a switch that happen to be adjacent.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = alpha),
                )
            }
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(
                        // 0.6 rather than alpha * 0.75, which lands at 0.375 on a disabled row —
                        // below even Material's 0.38 disabled token, on the text that explains why
                        // the control is off.
                        alpha = if (enabled) 0.75f else 0.6f,
                    ),
                )
            }
            if (!enabled && disabledReason != null) {
                if (disabledReasonAsInfo) {
                    // 10dp: the same gap a SectionCard puts between its rows, so the note sits like
                    // the card's other notes instead of hanging off the description.
                    InfoNote(
                        text = disabledReason,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Text(
                        text = disabledReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // null: the row above owns the click, so the switch is the state's picture, not a target.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A small hint line with an info icon in front. The icon is decorative (the text says it all), so
 * it carries no content description and a screen reader reads the text alone.
 */
@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current.copy(alpha = 0.75f),
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = AppIcons.Info,
            contentDescription = null,
            tint = color,
            // Sits on the first line of the text, not centred on a wrapped paragraph.
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Radio row, for a choice with more than a couple of options or with long labels — the launch-mode
 * list. Short either/or choices use [SegmentedChoice] instead.
 */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalContentColor.current.copy(alpha = alpha),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = alpha * 0.75f),
                )
            }
        }
    }
}

/**
 * The expressive answer to a short, mutually exclusive choice: a connected group of expressive
 * [FilledTonalToggleButton]s. Each option reshapes as it is pressed and again as it becomes the selected one
 * — the round-to-squarer morph the design system uses to mark selection, which a toggle button carries
 * by default — so the choice is read from shape *and* colour, never colour alone, and the unselected
 * options keep a tonal container instead of dissolving into the card behind them.
 *
 * Every option is a full 48dp target and shares the row equally, so a long translation wraps inside
 * its own button rather than pushing a neighbour off screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (T) -> String,
) {
    Row(
        // A toggle button reports Role.Checkbox, which is right for an independent on/off and wrong
        // here: these options are mutually exclusive. selectableGroup() plus the RadioButton role and
        // the selected flag make a screen reader announce "2 of 3, selected" instead of three unrelated
        // checkboxes, and it is the only thing that tells the user how many options there even are.
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            FilledTonalToggleButton(
                checked = isSelected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics {
                        // Qualified: the composable's own `selected` parameter shadows the semantics
                        // property of the same name inside this lambda.
                        this.role = Role.RadioButton
                        this.selected = isSelected
                    },
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    enabled: Boolean = true,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filterNumeric(decimal)) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = modifier,
    )
}

/**
 * A number entered with an explicit unit. Stacked rather than side by side on purpose: at a large
 * font scale a field and two unit labels cannot share one line without one of them being clipped.
 */
@Composable
internal fun DurationRow(
    label: String,
    value: String,
    unit: TimeUnitChoice,
    onValueChange: (String) -> Unit,
    onUnitChange: (TimeUnitChoice) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            decimal = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedChoice(
            options = TimeUnitChoice.entries,
            selected = unit,
            onSelect = onUnitChange,
            enabled = enabled,
        ) { candidate -> stringResource(candidate.labelRes) }
    }
}

/**
 * A bounded number is a slider, not a text field: the range is visible, an impossible value cannot be
 * typed, and the value itself stays on screen as text so it is never a position alone.
 */
@Composable
fun SliderRow(
    label: String,
    valueLabel: String,
    valueDescription: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalContentColor.current.copy(alpha = alpha),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = ExpressiveType.figureLabel,
                color = LocalContentColor.current.copy(alpha = alpha),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            // Left to itself a slider is announced as a bare number ("15.0"); this makes it a
            // sentence with its unit in it, in the device's language.
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = valueDescription
                },
        )
    }
}

/**
 * The floating controls for a running session: the expressive [HorizontalFloatingToolbar] with the
 * primary action docked to it as a floating action button. It stays above the navigation while the
 * content scrolls under it, so start, stop and pause are always one reach away.
 *
 * `expanded = true` permanently: this toolbar carries the app's primary action, and a toolbar that
 * collapsed itself on scroll would hide Start behind a gesture. The vibrant colour set is deliberate —
 * the toolbar floats over cards of its own tonal family, and the standard set makes the two blur into
 * one another.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionToolbar(
    fab: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = fab,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        // Content scrolls under a floating toolbar by design, so the shadow is what keeps it readable
        // as a layer above the cards rather than a shape drawn into one.
        expandedShadowElevation = 8.dp,
        content = content,
    )
}

/**
 * The one action the screen is about, docked to [SessionToolbar]: Start turning into Stop as one
 * object changing its shape and colour rather than two controls trading places. The shape travels on
 * the expressive slow spatial spring, the colours on the effects spring.
 *
 * A floating action button has no `enabled` parameter — Material deliberately gives it none — so
 * [enabled] `false` renders a *different* composable: the same shape and size with the disabled
 * treatment, no click, no ripple, and no click action in its semantics. Gating only the lambda would
 * leave a button that ripples under the finger and that TalkBack reads as "disabled" while still
 * offering an activate action that does nothing. The reasons a start is blocked are on screen right
 * above it in the blockers card, so an inert button here is never the only explanation.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val radius: Dp by animateDpAsState(
        // Stop is the squarer of the two shapes, Start the rounder: the morph is the state change.
        targetValue = if (destructive) SESSION_ACTION_STOP_CORNER else SESSION_ACTION_START_CORNER,
        animationSpec = ExpressiveMotion.slowSpatial(),
        label = "sessionActionRadius",
    )
    val container by animateColorAsState(
        targetValue = when {
            // Opaque, not a translucent overlay: this button is docked outside the toolbar's own
            // container, so a 12%-alpha disabled colour would let whatever card lies behind it show
            // through and read as a rendering fault rather than as a disabled control.
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = ExpressiveMotion.effects(),
        label = "sessionActionContainer",
    )
    val onContainer by animateColorAsState(
        targetValue = when {
            // onSurfaceVariant, not onSurface at 38%: a 38% glyph on this container is around 2:1,
            // under the 3:1 an icon needs, and the icon is the only thing saying what the button is.
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            destructive -> MaterialTheme.colorScheme.onError
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = ExpressiveMotion.effects(),
        label = "sessionActionContent",
    )
    val shape = RoundedCornerShape(radius)
    if (!enabled) {
        Surface(
            shape = shape,
            color = container,
            contentColor = onContainer,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(SESSION_ACTION_SIZE)
                .semantics {
                    contentDescription = label
                    disabled()
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        }
        return
    }
    FloatingToolbarDefaults.VibrantFloatingActionButton(
        onClick = onClick,
        shape = shape,
        containerColor = container,
        contentColor = onContainer,
        modifier = Modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = MaterialTheme.colorScheme.scrim,
                spotColor = MaterialTheme.colorScheme.scrim,
            )
            .border(
                width = 1.dp,
                color = onContainer.copy(alpha = 0.25f),
                shape = shape,
            )
            .semantics { contentDescription = label },
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(30.dp))
    }
}

/**
 * The two corners the primary action morphs between, and the size its disabled twin has to match.
 *
 * Named rather than inlined because the disabled branch must be pixel-identical to the FAB it replaces;
 * `shapes.extraLarge`/`large` are not usable here because a FAB's own container shape is a fixed 28dp
 * token, and a button that changed size between its states would read as two different controls.
 */
/** Widest a single column of cards gets before it stops being one column's worth of reading. */
private val CONTENT_MAX_WIDTH = 640.dp

/** Bottom padding above the floating toolbar: its own offset plus the room its FAB overhangs into. */
private val FLOATING_LAYER_MARGIN = 48.dp

/**
 * How much of the bottom of a [ScreenScaffold] with a floating layer that layer covers. Whatever is
 * scrolled to has to end this far above the bottom edge to be seen whole.
 */
val FloatingLayerClearance: Dp
    get() = FloatingToolbarDefaults.ContainerSize + FLOATING_LAYER_MARGIN

private val SESSION_ACTION_START_CORNER = 32.dp
private val SESSION_ACTION_STOP_CORNER = 20.dp
private val SESSION_ACTION_SIZE = 56.dp

/** An icon-only control. The description is not optional, which is why it has no default. */
@Composable
fun ToolbarAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * The session indicator: the expressive determinate [ContainedLoadingIndicator], showing how far the
 * wait for the next shot has run. The indicator morphs through the expressive shape set as it fills,
 * which is the motion the design system uses to say "working" without a spinner's ambiguity.
 *
 * Determinate on purpose. A spinner says "something is happening"; a timelapse raises the question
 * "how long until the next frame", and this answers it. It is decorative in the accessibility sense —
 * the same countdown is written out in words beside it — so it carries no content description, and its
 * size is fixed because there is no text inside it to scale.
 *
 * The progress it is handed is already animated, on the expressive spatial spring, so a shot landing
 * (progress snapping back to zero) travels instead of jumping.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgressRing(
    progress: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val sweep by animateFloatAsState(
        targetValue = if (active) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = ExpressiveMotion.spatial(),
        label = "progressRingSweep",
    )
    ContainedLoadingIndicator(
        progress = { sweep },
        modifier = modifier.size(56.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        indicatorColor = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Small print with an icon in front (a warning sign, an info mark), both in [color]. The words carry
 * the meaning, so the icon has no content description of its own.
 */
@Composable
fun IconText(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Icon plus words. The words are the state; the icon and the colour only reinforce them. */
@Composable
fun StatusChip(text: String, granted: Boolean, muted: Boolean = false) {
    // `granted` takes the container's own content colour rather than naming `primary`: this chip lands
    // on neutral, secondary and error cards, and under dynamic colour Material guarantees nothing about
    // primary-on-secondaryContainer. Only the *not granted* case names a role, because there the colour
    // carries meaning — and it is never the only signal, the words say the same thing.
    val color = when {
        granted -> LocalContentColor.current
        muted -> LocalContentColor.current.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (granted) AppIcons.Check else AppIcons.Pending,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun Badge(
    text: String,
    container: Color = MaterialTheme.colorScheme.tertiaryContainer,
    content: Color = MaterialTheme.colorScheme.onTertiaryContainer,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun ListRowButton(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    /** A short tag beside the title, such as "BETA". */
    badge: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (badge == null) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * An accordion row: an expandable header row that toggles [expanded] and animates [content] in and out.
 * Closed by default, with a smoothly rotating chevron indicator.
 */
@Composable
fun AccordionRow(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ExpressiveMotion.spatial(),
        label = "accordionChevronRotation",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(
                    role = Role.Button,
                    onClick = { onExpandedChange(!expanded) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
            Icon(
                imageVector = AppIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(chevronRotation),
                tint = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = ExpressiveMotion.spatial()) + fadeIn(animationSpec = ExpressiveMotion.effects()),
            exit = shrinkVertically(animationSpec = ExpressiveMotion.spatial()) + fadeOut(animationSpec = ExpressiveMotion.effects()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

private fun String.filterNumeric(decimal: Boolean): String {
    val allowed = buildString {
        this@filterNumeric.forEach { c ->
            when {
                c.isDigit() -> append(c)
                decimal && (c == '.' || c == ',') && none { it == '.' } -> append('.')
            }
        }
    }
    return allowed.take(9)
}

/**
 * A signed alignment offset, in the viewer's own locale.
 *
 * The locale comes from [LocalResources] rather than `Locale.getDefault()`: the latter is read once
 * and never invalidates, so a language change would leave a stale decimal separator on screen.
 */
@Composable
internal fun formatOffsetDegrees(valueDegrees: Float): String {
    val locale = LocalResources.current.configuration.locales[0]
    return String.format(locale, "%+.1f°", valueDegrees)
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Hides the system status and navigation bars while the Composable is in composition (Immersive Fullscreen),
 * using [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE] so edge swipes briefly reveal
 * transient bars without resizing the layout. Restores system bars upon disposal.
 */
@Composable
fun ImmersiveModeEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            val previousBehavior = insetsController.systemBarsBehavior
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())

            onDispose {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = previousBehavior
            }
        } else {
            onDispose {}
        }
    }
}

