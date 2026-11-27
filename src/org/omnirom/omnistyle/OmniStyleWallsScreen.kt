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
@file:OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package org.omnirom.omnistyle

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.axion.compose.expressive.ExpressiveMorphButton
import com.android.axion.compose.expressive.ExpressiveMorphLoadingIndicator
import com.android.axion.compose.expressive.ExpressiveSegmentOption
import com.android.axion.compose.expressive.ExpressiveSegmentedButtonRow
import com.android.axion.compose.expressive.ExpressiveSnackbarHost
import com.android.axion.compose.expressive.ExpressiveToolbarIconButton
import com.android.axion.compose.expressive.rememberExpressiveHaptics
import com.android.axion.compose.scaffold.AxionScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WallLocationTab {
    LOCAL,
    REMOTE,
}

private sealed interface WallpaperSelectionTarget {
    data class Local(val item: LocalWallpaperItem) : WallpaperSelectionTarget
    data class Remote(val item: RemoteMediaItem) : WallpaperSelectionTarget
}

@Composable
fun OmniStyleWallsScreen(
    title: String,
    remoteOnlyFilterTag: String? = null,
    initialFilterTag: String? = null,
    onBackClick: () -> Unit,
    onWallpaperApplied: () -> Unit = {},
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val haptics = rememberExpressiveHaptics()
    val snackbarHostState = remember { SnackbarHostState() }

    val isRemoteOnly = !remoteOnlyFilterTag.isNullOrBlank()
    var locationTab by rememberSaveable {
        mutableStateOf(if (isRemoteOnly) WallLocationTab.REMOTE else WallLocationTab.LOCAL)
    }
    var sortByCreator by rememberSaveable { mutableStateOf(false) }

    var localLoading by remember { mutableStateOf(!isRemoteOnly) }
    var localItems by remember { mutableStateOf<List<LocalWallpaperItem>>(emptyList()) }

    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf(false) }
    var remoteItems by remember { mutableStateOf<List<RemoteMediaItem>>(emptyList()) }

    var pendingTarget by remember { mutableStateOf<WallpaperSelectionTarget?>(null) }
    var applyingKey by remember { mutableStateOf<String?>(null) }
    var savingKey by remember { mutableStateOf<String?>(null) }

    val activeFilter = remoteOnlyFilterTag ?: initialFilterTag

    fun showMessage(messageResId: Int, duration: SnackbarDuration = SnackbarDuration.Short) {
        val message = context.getString(messageResId)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = duration,
                withDismissAction = duration == SnackbarDuration.Long,
            )
        }
    }

    fun loadRemoteWalls() {
        if (!HeaderRepository.isNetworkConnected(context)) {
            remoteError = true
            showMessage(R.string.no_network_message)
            return
        }
        scope.launch {
            remoteLoading = true
            remoteError = false
            HeaderRepository.fetchRemoteWallpapers(activeFilter)
                .onSuccess { remoteItems = it }
                .onFailure {
                    remoteError = true
                    showMessage(
                        R.string.download_wallpaper_list_failed_notice,
                        SnackbarDuration.Long,
                    )
                }
            remoteLoading = false
        }
    }

    LaunchedEffect(isRemoteOnly, activeFilter) {
        if (!isRemoteOnly) {
            localLoading = true
            localItems = HeaderRepository.loadLocalWallpapers(context)
            localLoading = false
        } else {
            loadRemoteWalls()
        }
    }

    fun applyWallpaperFlags(target: WallpaperSelectionTarget, flags: Int) {
        if (applyingKey != null) return
        scope.launch {
            val key = when (target) {
                is WallpaperSelectionTarget.Local -> target.item.imageName
                is WallpaperSelectionTarget.Remote -> target.item.fileName
            }
            applyingKey = key
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val wpm = WallpaperManager.getInstance(context.applicationContext)
                    when (target) {
                        is WallpaperSelectionTarget.Local -> {
                            val bmp = BitmapFactory.decodeResource(
                                context.resources,
                                target.item.resId,
                            ) ?: error("Failed to decode wallpaper resource")
                            try {
                                wpm.setBitmap(bmp, null, true, flags)
                            } finally {
                                bmp.recycle()
                            }
                        }

                        is WallpaperSelectionTarget.Remote -> {
                            val cached = HeaderRepository.downloadToCacheFile(
                                context = context,
                                url = target.item.fullUrl,
                                prefix = "wall_",
                            ) ?: error("Failed downloading remote wallpaper")
                            val bmp = BitmapFactory.decodeFile(cached.absolutePath)
                                ?: error("Failed decoding remote wallpaper")
                            try {
                                wpm.setBitmap(bmp, null, true, flags)
                            } finally {
                                bmp.recycle()
                                cached.delete()
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            applyingKey = null
            if (success) {
                haptics.performConfirm()
                showMessage(R.string.wallpaper_applied_notice)
                onWallpaperApplied()
            } else {
                haptics.performBoundaryBump()
                showMessage(R.string.download_wallpaper_failed_notice, SnackbarDuration.Long)
            }
        }
    }

    fun saveWallpaperToGallery(target: WallpaperSelectionTarget) {
        if (savingKey != null) return
        scope.launch {
            val key = when (target) {
                is WallpaperSelectionTarget.Local -> target.item.imageName
                is WallpaperSelectionTarget.Remote -> target.item.fileName
            }
            savingKey = key
            if (target is WallpaperSelectionTarget.Remote) {
                showMessage(R.string.download_wallpaper_notice)
            }
            val saved = when (target) {
                is WallpaperSelectionTarget.Local -> HeaderRepository.savePackageDrawableToPictures(
                    context = context,
                    packageName = target.item.packageName,
                    resId = target.item.resId,
                    imageName = target.item.imageName,
                )

                is WallpaperSelectionTarget.Remote -> HeaderRepository.downloadToPictures(
                    context = context,
                    url = target.item.fullUrl,
                    fileName = target.item.fileName,
                )
            }
            savingKey = null
            if (saved != null) {
                haptics.performConfirm()
                showMessage(R.string.header_saved_pictures_notice)
            } else {
                haptics.performBoundaryBump()
                showMessage(R.string.wallpaper_save_failed_notice, SnackbarDuration.Long)
            }
        }
    }

    val sortedLocal = remember(localItems, sortByCreator) {
        if (sortByCreator) {
            localItems.sortedBy { it.creator.lowercase() }
        } else {
            localItems.sortedBy { it.displayName.lowercase() }
        }
    }
    val sortedRemote = remember(remoteItems, sortByCreator) {
        if (sortByCreator) {
            remoteItems.sortedBy { it.creator.lowercase() }
        } else {
            remoteItems.sortedBy { it.displayName.lowercase() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AxionScaffold(
            title = title,
            onBackClick = onBackClick,
            collapsedByDefault = false,
            actions = {
                if (!isRemoteOnly) {
                    ExpressiveToolbarIconButton(
                        icon = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(
                            if (sortByCreator) {
                                R.string.sort_by_default_menu
                            } else {
                                R.string.sort_by_creator_menu
                            }
                        ),
                        selected = sortByCreator,
                        onClick = { sortByCreator = !sortByCreator },
                    )
                }
            },
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val columns = if (maxWidth >= 600.dp) 3 else 2

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 32.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (!isRemoteOnly) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ExpressiveSegmentedButtonRow(
                                options = listOf(
                                    ExpressiveSegmentOption(
                                        value = WallLocationTab.LOCAL,
                                        label = stringResource(R.string.wallpaper_location_local),
                                        icon = Icons.Rounded.Collections,
                                    ),
                                    ExpressiveSegmentOption(
                                        value = WallLocationTab.REMOTE,
                                        label = stringResource(R.string.wallpaper_location_remote),
                                        icon = Icons.Rounded.CloudQueue,
                                    ),
                                ),
                                selectedValue = locationTab,
                                onValueSelected = { next ->
                                    locationTab = next
                                    if (next == WallLocationTab.REMOTE && remoteItems.isEmpty()) {
                                        loadRemoteWalls()
                                    }
                                },
                            )
                        }
                    }

                    if (locationTab == WallLocationTab.LOCAL) {
                        if (localLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CenteredExpressiveLoader()
                            }
                        } else if (sortedLocal.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                EmptyStateBentoCard(stringResource(R.string.wallpaper_empty_state))
                            }
                        } else {
                            items(sortedLocal, key = { it.imageName }) { wall ->
                                WallpaperBentoCard(
                                    title = wall.displayName,
                                    creator = wall.creator,
                                    source = PreviewImageSource.PackageDrawable(
                                        packageName = wall.packageName,
                                        resId = wall.resId,
                                        maxDimensionPx = 900,
                                    ),
                                    isBusy = applyingKey == wall.imageName || savingKey == wall.imageName,
                                    onClick = {
                                        haptics.performStepTick()
                                        pendingTarget = WallpaperSelectionTarget.Local(wall)
                                    },
                                    onLongClick = null,
                                )
                            }
                        }
                    } else {
                        if (remoteLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CenteredExpressiveLoader()
                            }
                        } else if (remoteError || sortedRemote.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                OfflineOrErrorBentoCard(
                                    message = stringResource(
                                        if (!HeaderRepository.isNetworkConnected(context)) {
                                            R.string.no_network_message
                                        } else {
                                            R.string.download_wallpaper_list_failed_notice
                                        }
                                    ),
                                    onRetry = { loadRemoteWalls() },
                                )
                            }
                        } else {
                            items(sortedRemote, key = { it.fileName }) { wall ->
                                WallpaperBentoCard(
                                    title = wall.displayName,
                                    creator = wall.creator,
                                    source = PreviewImageSource.RemoteUrl(
                                        url = wall.thumbUrl,
                                        maxDimensionPx = 900,
                                    ),
                                    isBusy = applyingKey == wall.fileName || savingKey == wall.fileName,
                                    onClick = {
                                        haptics.performStepTick()
                                        pendingTarget = WallpaperSelectionTarget.Remote(wall)
                                    },
                                    onLongClick = null,
                                )
                            }
                        }
                    }
                }
            }
        }

        ExpressiveSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    val target = pendingTarget
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val previewTitle = when (target) {
            is WallpaperSelectionTarget.Local -> target.item.displayName
            is WallpaperSelectionTarget.Remote -> target.item.displayName
        }.ifBlank { stringResource(R.string.wallpaper_default_name) }
        val previewCreator = when (target) {
            is WallpaperSelectionTarget.Local -> target.item.creator
            is WallpaperSelectionTarget.Remote -> target.item.creator
        }
        val previewSource = remember(target) {
            when (target) {
                is WallpaperSelectionTarget.Local -> PreviewImageSource.PackageDrawable(
                    packageName = target.item.packageName,
                    resId = target.item.resId,
                    maxDimensionPx = 1440,
                )

                is WallpaperSelectionTarget.Remote -> PreviewImageSource.RemoteUrl(
                    url = target.item.fullUrl,
                    maxDimensionPx = 1440,
                )
            }
        }
        val dimensions by produceState<ImageDimensions?>(
            initialValue = AsyncPreviewCache.getCachedDimensions(previewSource.cacheKey),
            key1 = previewSource.cacheKey,
        ) {
            if (value == null) {
                value = AsyncPreviewCache.loadDimensions(context, previewSource)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { pendingTarget = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = previewTitle,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (previewCreator.isNotBlank()) {
                        val creatorLabel = stringResource(R.string.header_creator_label)
                            .trim()
                            .trimEnd(':', '\uFF1A')
                            .trim()
                        Text(
                            text = stringResource(
                                R.string.header_creator_format,
                                creatorLabel,
                                previewCreator,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val previewAspect = (dimensions?.aspectRatio ?: 0.75f).coerceIn(0.45f, 2.0f)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 180.dp, max = 360.dp)
                            .aspectRatio(previewAspect, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncPreviewImage(
                            source = previewSource,
                            contentDescription = previewTitle,
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                dimensions?.let { dims ->
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TonalMetadataChip(
                            text = stringResource(
                                R.string.wallpaper_resolution_format,
                                dims.width,
                                dims.height,
                            ),
                            icon = Icons.Rounded.AspectRatio,
                            tabular = true,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.wallpaper_type_dialog_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                ExpressiveMorphButton(
                    onClick = {
                        pendingTarget = null
                        applyWallpaperFlags(
                            target,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(text = stringResource(R.string.wallpaper_type_both))
                }

                ExpressiveMorphButton(
                    onClick = {
                        pendingTarget = null
                        applyWallpaperFlags(target, WallpaperManager.FLAG_SYSTEM)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(text = stringResource(R.string.wallpaper_type_system))
                }

                ExpressiveMorphButton(
                    onClick = {
                        pendingTarget = null
                        applyWallpaperFlags(target, WallpaperManager.FLAG_LOCK)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(text = stringResource(R.string.wallpaper_type_lock))
                }

                ExpressiveMorphButton(
                    onClick = {
                        pendingTarget = null
                        saveWallpaperToGallery(target)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(text = stringResource(R.string.wallpaper_save_gallery_action))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallpaperBentoCard(
    title: String,
    creator: String,
    source: PreviewImageSource,
    isBusy: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        ) {
            AsyncPreviewImage(
                source = source,
                contentDescription = title,
            )
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.wallpaper_default_name) },
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (creator.isNotBlank()) {
                Text(
                    text = creator,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
