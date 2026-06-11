package com.nyora.windows.bridge

import com.nyora.hasan72341.shared.model.Manga
import kotlinx.serialization.Serializable

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val lang: String,
    val engine: String,
    val contentType: String,
    val isBroken: Boolean,
    val isInstalled: Boolean,
)

@Serializable
data class CatalogResponse(val entries: List<CatalogEntry>)

@Serializable
data class DownloadDto(
    val id: String,
    val sourceId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val totalPages: Int = 0,
    val completedPages: Int = 0,
    val failedPages: Int = 0,
    val status: String,
    val filePath: String? = null,
    val error: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Serializable
data class DownloadsResponse(val entries: List<DownloadDto>)

@Serializable
data class DownloadResponse(val entry: DownloadDto)

@Serializable
data class LocalCbzEntry(val path: String, val name: String, val sizeBytes: Long)

@Serializable
data class LocalScanResponse(val entries: List<LocalCbzEntry>)

@Serializable
data class LocalChapterResponse(val name: String, val pageCount: Int, val pageUrls: List<String>)

@Serializable
data class CategoryDto(val id: Long, val title: String, val mangaCount: Int)

@Serializable
data class CategoriesResponse(val categories: List<CategoryDto>)

@Serializable
data class GlobalSearchGroup(
    val sourceId: String,
    val sourceName: String,
    val entries: List<Manga>,
    val error: String? = null,
)

@Serializable
data class GlobalSearchResponse(val query: String, val groups: List<GlobalSearchGroup>)

@Serializable
data class MangaCategoriesResponse(val categories: List<CategoryDto>)
