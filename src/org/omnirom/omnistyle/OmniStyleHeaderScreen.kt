/*
 * Copyright (C) 2026 The ExpressiveOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.omnirom.omnistyle

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.axion.compose.expressive.ExpressiveMorphButton
import com.android.axion.compose.expressive.ExpressiveMorphLoadingIndicator
import com.android.axion.compose.expressive.ExpressiveSegmentOption
import com.android.axion.compose.expressive.ExpressiveSegmentedButtonRow
import com.android.axion.compose.expressive.ExpressiveShapePreset
import com.android.axion.compose.expressive.ExpressiveToolbarPrimaryPill
import com.android.axion.compose.expressive.rememberExpressiveHaptics
import com.android.axion.compose.scaffold.AxionScaffold
import com.android.axion.compose.theme.EmphasizedTabularStyle
import com.android.axion.compose.theme.withTabularNums
import kotlinx.coroutines.launch

private enum class HeaderSourceTab {
    LOCAL,
    REMOTE,
}

private data class ActiveHeaderSelection(
    val provider: String,
    val staticImage: String,
    val daylightPack: String,
    val fileUri: String,
)

private fun readActiveHeaderSelection(context: Context): ActiveHeaderSelection {
    val cr = context.contentResolver
    return ActiveHeaderSelection(
        provider = Settings.System.getString(cr, Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER)
            .orEmpty(),
        staticImage = Settings.System.getString(cr, Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE)
            .orEmpty(),
        daylightPack = Settings.System.getString(cr, Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK)
            .orEmpty(),
        fileUri = Settings.System.getString(cr, Settings.System.STATUS_BAR_FILE_HEADER_IMAGE)
            .orEmpty(),
    )
}

@Composable
private fun rememberActiveHeaderSelection(): ActiveHeaderSelection {
    val context = LocalContext.current
    var selection by remember(context) { mutableStateOf(readActiveHeaderSelection(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                selection = readActiveHeaderSelection(context)
            }
        }
        val uris = listOfNotNull(
            Settings.System.getUriFor(Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER),
            Settings.System.getUriFor(Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE),
            Settings.System.getUriFor(Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK),
            Settings.System.getUriFor(Settings.System.STATUS_BAR_FILE_HEADER_IMAGE),
        )
        uris.forEach { resolver.registerContentObserver(it, false, observer) }
        selection = readActiveHeaderSelection(context)
        onDispose {
            resolver.unregisterContentObserver(observer)
        }
    }

    return selection
}

private sealed interface PreviewModalTarget {
    data class Local(val item: DaylightHeaderItem, val packLabel: String) : PreviewModalTarget
    data class Remote(val item: RemoteMediaItem) : PreviewModalTarget
}

@Composable
fun OmniStyleHeaderScreen(
    title: String,
    pickerMode: Boolean,
    singleThemePackOnly: Boolean = false,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberExpressiveHaptics()
    val activeSelection = rememberActiveHeaderSelection()

    var sourceTab by rememberSaveable { mutableStateOf(HeaderSourceTab.LOCAL) }
    var packsLoading by remember { mutableStateOf(true) }
    var packs by remember { mutableStateOf<List<DaylightHeaderPack>>(emptyList()) }
    var selectedPackIndex by rememberSaveable { mutableIntStateOf(0) }

    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf(false) }
    var remoteItems by remember { mutableStateOf<List<RemoteMediaItem>>(emptyList()) }
    var selectedRemoteTag by rememberSaveable { mutableStateOf("") }
    var appliedRemoteFileName by rememberSaveable { mutableStateOf("") }
    var busyDownloadFileName by remember { mutableStateOf<String?>(null) }

    var previewModalTarget by remember { mutableStateOf<PreviewModalTarget?>(null) }

    LaunchedEffect(singleThemePackOnly) {
        packsLoading = true
        packs = if (singleThemePackOnly) {
            listOfNotNull(HeaderRepository.loadThemeHeaderPack(context))
        } else {
            HeaderRepository.loadAvailableHeaderPacks(context)
        }
        if (packs.isNotEmpty()) {
            val activeIdx = packs.indexOfFirst { it.packId == activeSelection.daylightPack }
            if (activeIdx >= 0) {
                selectedPackIndex = activeIdx
            }
        }
        packsLoading = false
    }

    fun triggerRemoteLoad() {
        if (!HeaderRepository.isNetworkConnected(context)) {
            remoteError = true
            Toast.makeText(context, R.string.no_network_message, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            remoteLoading = true
            remoteError = false
            HeaderRepository.fetchRemoteHeaders()
                .onSuccess { fetched ->
                    remoteItems = fetched
                    val tags = fetched.map { it.tag }.distinct().sorted()
                    if (selectedRemoteTag !in tags) {
                        selectedRemoteTag = tags.firstOrNull().orEmpty()
                    }
                }
                .onFailure {
                    remoteError = true
                    Toast.makeText(
                        context,
                        R.string.download_wallpaper_list_failed_notice,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            remoteLoading = false
        }
    }

    fun applyStaticHeader(item: DaylightHeaderItem) {
        Settings.System.putString(
            context.contentResolver,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE,
            item.staticSettingValue,
        )
        Settings.System.putString(
            context.contentResolver,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
            "static",
        )
        haptics.performConfirm()
        Toast.makeText(context, R.string.custom_header_image_notice, Toast.LENGTH_SHORT).show()
    }

    fun applyDynamicPack(pack: DaylightHeaderPack) {
        Settings.System.putString(
            context.contentResolver,
            Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK,
            pack.packId,
        )
        Settings.System.putString(
            context.contentResolver,
            Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
            "daylight",
        )
        haptics.performConfirm()
        Toast.makeText(context, R.string.custom_header_pack_notice, Toast.LENGTH_SHORT).show()
    }

    fun applyRemoteHeader(item: RemoteMediaItem) {
        if (busyDownloadFileName != null) return
        scope.launch {
            busyDownloadFileName = item.fileName
            Toast.makeText(context, R.string.download_header_notice, Toast.LENGTH_SHORT).show()
            val file = HeaderRepository.downloadToCacheFile(
                context = context,
                url = item.fullUrl,
                prefix = "header_",
            )
            busyDownloadFileName = null
            if (file != null && file.exists()) {
                val imageUri = Uri.fromFile(file)
                Settings.System.putString(
                    context.contentResolver,
                    Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
                    "file",
                )
                Settings.System.putString(
                    context.contentResolver,
                    Settings.System.STATUS_BAR_FILE_HEADER_IMAGE,
                    imageUri.toString(),
                )
                appliedRemoteFileName = item.fileName
                haptics.performConfirm()
                Toast.makeText(context, R.string.custom_header_image_notice, Toast.LENGTH_SHORT)
                    .show()
            } else {
                haptics.performBoundaryBump()
                Toast.makeText(context, R.string.download_header_failed_notice, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    fun saveRemoteHeaderToPictures(item: RemoteMediaItem) {
        scope.launch {
            Toast.makeText(context, R.string.download_header_notice, Toast.LENGTH_SHORT).show()
            val savedUri = HeaderRepository.downloadToPictures(
                context = context,
                url = item.fullUrl,
                fileName = item.fileName,
            )
            if (savedUri != null) {
                haptics.performConfirm()
                Toast.makeText(context, R.string.header_saved_pictures_notice, Toast.LENGTH_SHORT)
                    .show()
            } else {
                haptics.performBoundaryBump()
                Toast.makeText(context, R.string.download_header_failed_notice, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    AxionScaffold(
        title = title,
        onBackClick = onBackClick,
        collapsedByDefault = false,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val columns = if (maxWidth >= 600.dp) 2 else 1
            val currentPack = packs.getOrNull(selectedPackIndex.coerceIn(0, (packs.size - 1).coerceAtLeast(0)))
            val remoteTags = remember(remoteItems) {
                remoteItems.map { it.tag }.distinct().sorted()
            }
            val filteredRemoteItems = remember(remoteItems, selectedRemoteTag) {
                if (selectedRemoteTag.isBlank()) {
                    remoteItems
                } else {
                    remoteItems.filter { it.tag == selectedRemoteTag }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 32.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!singleThemePackOnly) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ExpressiveSegmentedButtonRow(
                            options = listOf(
                                ExpressiveSegmentOption(
                                    value = HeaderSourceTab.LOCAL,
                                    label = stringResource(R.string.header_location_local),
                                    icon = Icons.Rounded.Collections,
                                ),
                                ExpressiveSegmentOption(
                                    value = HeaderSourceTab.REMOTE,
                                    label = stringResource(R.string.header_location_online),
                                    icon = Icons.Rounded.CloudQueue,
                                ),
                            ),
                            selectedValue = sourceTab,
                            onValueSelected = { next ->
                                sourceTab = next
                                if (next == HeaderSourceTab.REMOTE && remoteItems.isEmpty()) {
                                    triggerRemoteLoad()
                                }
                            },
                        )
                    }
                }

                if (sourceTab == HeaderSourceTab.LOCAL) {
                    if (packsLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CenteredExpressiveLoader()
                        }
                    } else if (packs.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyStateBentoCard(
                                message = stringResource(R.string.header_empty_state),
                            )
                        }
                    } else {
                        if (packs.size > 1) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                ) {
                                    itemsIndexed(packs, key = { _, p -> p.packId }) { idx, pack ->
                                        val isSelectedPack = idx == selectedPackIndex
                                        ExpressiveMorphButton(
                                            onClick = { selectedPackIndex = idx },
                                            selected = isSelectedPack,
                                            hapticOnClick = false,
                                            contentPadding = PaddingValues(
                                                horizontal = 16.dp,
                                                vertical = 10.dp,
                                            ),
                                            containerColor = if (isSelectedPack) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                            contentColor = if (isSelectedPack) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        ) {
                                            Text(
                                                text = pack.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (currentPack != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                val isPackActive = activeSelection.provider == "daylight" &&
                                    activeSelection.daylightPack == currentPack.packId
                                HeaderPackSummaryCard(
                                    pack = currentPack,
                                    isPackActive = isPackActive,
                                    onApplyPack = { applyDynamicPack(currentPack) },
                                )
                            }

                            items(
                                items = currentPack.items,
                                key = { "${it.packageName}/${it.imageName}:${it.scheduleTag.orEmpty()}" },
                            ) { item ->
                                val isSelected = activeSelection.provider == "static" &&
                                    activeSelection.staticImage == item.staticSettingValue
                                HeaderPreviewBentoCard(
                                    title = item.displayName,
                                    subtitle = item.scheduleTag,
                                    source = PreviewImageSource.PackageDrawable(
                                        packageName = item.packageName,
                                        resId = item.resId,
                                    ),
                                    isSelected = isSelected,
                                    isBusy = false,
                                    onClick = {
                                        if (pickerMode) {
                                            applyStaticHeader(item)
                                        } else {
                                            haptics.performStepTick()
                                            previewModalTarget =
                                                PreviewModalTarget.Local(item, currentPack.label)
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performLongPress()
                                        previewModalTarget =
                                            PreviewModalTarget.Local(item, currentPack.label)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    if (remoteLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CenteredExpressiveLoader()
                        }
                    } else if (remoteError || remoteItems.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            OfflineOrErrorBentoCard(
                                message = stringResource(
                                    if (!HeaderRepository.isNetworkConnected(context)) {
                                        R.string.no_network_message
                                    } else {
                                        R.string.download_wallpaper_list_failed_notice
                                    }
                                ),
                                onRetry = { triggerRemoteLoad() },
                            )
                        }
                    } else {
                        if (remoteTags.size > 1) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                ) {
                                    itemsIndexed(remoteTags, key = { _, tag -> tag }) { _, tag ->
                                        val selected = tag == selectedRemoteTag
                                        ExpressiveMorphButton(
                                            onClick = { selectedRemoteTag = tag },
                                            selected = selected,
                                            hapticOnClick = false,
                                            contentPadding = PaddingValues(
                                                horizontal = 16.dp,
                                                vertical = 10.dp,
                                            ),
                                            containerColor = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                            contentColor = if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        ) {
                                            Text(text = tag.replaceFirstChar { it.uppercase() })
                                        }
                                    }
                                }
                            }
                        }

                        items(
                            items = filteredRemoteItems,
                            key = { it.fileName },
                        ) { remoteItem ->
                            val isSelected = activeSelection.provider == "file" &&
                                appliedRemoteFileName == remoteItem.fileName
                            HeaderPreviewBentoCard(
                                title = remoteItem.displayName,
                                subtitle = remoteItem.creator,
                                source = PreviewImageSource.RemoteUrl(remoteItem.thumbUrl),
                                isSelected = isSelected,
                                isBusy = busyDownloadFileName == remoteItem.fileName,
                                onClick = {
                                    if (pickerMode) {
                                        applyRemoteHeader(remoteItem)
                                    } else {
                                        haptics.performStepTick()
                                        previewModalTarget = PreviewModalTarget.Remote(remoteItem)
                                    }
                                },
                                onLongClick = {
                                    haptics.performLongPress()
                                    saveRemoteHeaderToPictures(remoteItem)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val currentModal = previewModalTarget
    if (currentModal != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { previewModalTarget = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val (previewTitle, previewSubtitle, previewSource) = when (currentModal) {
                    is PreviewModalTarget.Local -> Triple(
                        currentModal.item.displayName,
                        currentModal.packLabel,
                        PreviewImageSource.PackageDrawable(
                            packageName = currentModal.item.packageName,
                            resId = currentModal.item.resId,
                            maxDimensionPx = 1440,
                        ),
                    )

                    is PreviewModalTarget.Remote -> Triple(
                        currentModal.item.displayName,
                        currentModal.item.creator,
                        PreviewImageSource.RemoteUrl(
                            url = currentModal.item.fullUrl,
                            maxDimensionPx = 1440,
                        ),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = previewTitle,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = previewSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 5.5f)
                        .clip(RoundedCornerShape(24.dp)),
                ) {
                    AsyncPreviewImage(
                        source = previewSource,
                        contentDescription = previewTitle,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentModal is PreviewModalTarget.Remote) {
                        ExpressiveMorphButton(
                            onClick = {
                                saveRemoteHeaderToPictures(currentModal.item)
                                previewModalTarget = null
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        ) {
                            Text(text = stringResource(R.string.header_save_image_action))
                        }
                    }

                    ExpressiveToolbarPrimaryPill(
                        label = stringResource(R.string.header_apply_image_action),
                        icon = Icons.Rounded.Check,
                        onClick = {
                            when (currentModal) {
                                is PreviewModalTarget.Local -> applyStaticHeader(currentModal.item)
                                is PreviewModalTarget.Remote -> applyRemoteHeader(currentModal.item)
                            }
                            previewModalTarget = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderPackSummaryCard(
    pack: DaylightHeaderPack,
    isPackActive: Boolean,
    onApplyPack: () -> Unit,
) {
    val scheduleText = when (pack.scheduleKind) {
        PackScheduleKind.HOURLY -> stringResource(R.string.header_pack_mode_hourly)
        PackScheduleKind.CALENDAR -> stringResource(R.string.header_pack_mode_calendar)
        PackScheduleKind.RANDOM -> stringResource(R.string.header_pack_mode_random)
        PackScheduleKind.INTERVAL ->
            stringResource(R.string.header_pack_mode_interval, pack.intervalMinutes)
        PackScheduleKind.COLLECTION -> stringResource(R.string.header_pack_mode_collection)
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isPackActive) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.label,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!pack.creator.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.header_creator_label) + pack.creator,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isPackActive) {
                    SelectedPillBadge(label = stringResource(R.string.header_pack_active_badge))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TonalMetadataChip(
                        text = stringResource(R.string.header_pack_items_count, pack.items.size),
                        tabular = true,
                    )
                    TonalMetadataChip(
                        text = scheduleText,
                        icon = Icons.Rounded.Schedule,
                        tabular = pack.scheduleKind == PackScheduleKind.INTERVAL,
                    )
                }

                if (!isPackActive) {
                    ExpressiveMorphButton(
                        onClick = onApplyPack,
                        restingPreset = ExpressiveShapePreset.PILL,
                        pressedPreset = ExpressiveShapePreset.SQUIRCLE,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.header_apply_pack_action),
                            style = MaterialTheme.typography.labelMediumEmphasized,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderPreviewBentoCard(
    title: String,
    subtitle: String?,
    source: PreviewImageSource,
    isSelected: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(
                if (isSelected) {
                    Modifier.border(
                        BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary),
                        cardShape,
                    )
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 5.2f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            ) {
                AsyncPreviewImage(
                    source = source,
                    contentDescription = title,
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.28f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.16f),
                                )
                            )
                        )
                )

                if (!subtitle.isNullOrBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMediumEmphasized.withTabularNums(),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                if (isSelected) {
                    SelectedPillBadge(
                        label = stringResource(R.string.header_selected_badge),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    )
                }

                if (isBusy) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveMorphLoadingIndicator(size = 44.dp, contained = true)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun SelectedPillBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMediumEmphasized,
            )
        }
    }
}

@Composable
internal fun TonalMetadataChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tabular: Boolean = false,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = if (tabular) {
                    EmphasizedTabularStyle(MaterialTheme.typography.labelMediumEmphasized)
                } else {
                    MaterialTheme.typography.labelMediumEmphasized
                },
            )
        }
    }
}

@Composable
internal fun CenteredExpressiveLoader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveMorphLoadingIndicator(size = 52.dp, contained = true)
    }
}

@Composable
internal fun EmptyStateBentoCard(message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun OfflineOrErrorBentoCard(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ExpressiveMorphButton(onClick = onRetry) {
                Text(text = stringResource(R.string.retry_action))
            }
        }
    }
}
