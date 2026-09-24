import Foundation
import Combine
import Photos
import AVFoundation
import UIKit
import ImageIO

@MainActor
final class PhoneMediaLibrary: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    @Published private(set) var imported: [PhoneMedia] = []
    @Published private(set) var photos: [PhoneMedia] = []
    @Published private(set) var downloads: [PhoneMedia] = []
    @Published private(set) var authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published private(set) var isScanning = false
    @Published var error: String?
    let directory: URL
    private var downloadURLs: [String: URL] = [:]
    private var scanGeneration = UUID()
    /// PhotoKit fetch snapshots are immutable while a detached scan reads them.
    private struct PhotoScanResult: @unchecked Sendable {
        let assets: PHFetchResult<PHAsset>
        let records: [PhoneMedia]
    }
    private var photoFetchResult: PHFetchResult<PHAsset>?
    private var photoScanTask: Task<PhotoScanResult?, Never>?
    private var observing = false
    var items: [PhoneMedia] { imported + photos + downloads }
    private var indexURL: URL { directory.appendingPathComponent("index.json") }

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MediaLibrary", isDirectory: true)
        super.init()
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: indexURL), let records = try? JSONDecoder().decode([PhoneMedia].self, from: data) {
            imported = records
        }
    }
    deinit {
        photoScanTask?.cancel()
        if observing { PHPhotoLibrary.shared().unregisterChangeObserver(self) }
    }
    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor [weak self] in self?.applyPhotoChange(changeInstance) }
    }
    func requestPhotos() async {
        authorization = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        photoScanTask?.cancel()
        photoScanTask = nil
        photoFetchResult = nil
        refreshPhotos()
    }
    func refreshPhotos() {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let permissionChanged = authorization != status
        authorization = status
        guard status == .authorized || status == .limited else {
            photoScanTask?.cancel(); photoScanTask = nil
            scanGeneration = UUID()
            photoFetchResult = nil; photos = []; isScanning = false
            return
        }
        if !observing { PHPhotoLibrary.shared().register(self); observing = true }
        if permissionChanged {
            photoScanTask?.cancel(); photoScanTask = nil
            photoFetchResult = nil
        }
        guard photoFetchResult == nil, photoScanTask == nil else { return }
        scanPhotos(assets: nil, changedIDs: [], reuseExisting: false)
    }

    private func applyPhotoChange(_ change: PHChange) {
        guard authorization == .authorized || authorization == .limited else { return }
        guard let previous = photoFetchResult else {
            photoScanTask?.cancel()
            photoScanTask = nil
            refreshPhotos()
            return
        }
        guard let details = change.changeDetails(for: previous) else { return }
        let changedIDs = Set((details.insertedObjects + details.changedObjects).map(\.localIdentifier))
        scanPhotos(assets: details.fetchResultAfterChanges, changedIDs: changedIDs,
                   reuseExisting: details.hasIncrementalChanges)
    }

    private func scanPhotos(assets: PHFetchResult<PHAsset>?, changedIDs: Set<String>, reuseExisting: Bool) {
        photoScanTask?.cancel()
        scanGeneration = UUID()
        let generation = scanGeneration
        let cached = reuseExisting ? Dictionary(uniqueKeysWithValues: photos.map { ($0.id, $0) }) : [:]
        isScanning = true
        let task = Task.detached(priority: .userInitiated) { () -> PhotoScanResult? in
            let resolvedAssets: PHFetchResult<PHAsset>
            if let assets {
                resolvedAssets = assets
            } else {
                let options = PHFetchOptions()
                options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d", PHAssetMediaType.video.rawValue, PHAssetMediaType.image.rawValue)
                options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
                resolvedAssets = PHAsset.fetchAssets(with: options)
            }
            var result: [PhoneMedia] = []
            result.reserveCapacity(resolvedAssets.count)
            resolvedAssets.enumerateObjects { asset, _, stop in
                if Task.isCancelled { stop.pointee = true; return }
                let id = "photos:" + asset.localIdentifier
                if !changedIDs.contains(asset.localIdentifier), let existing = cached[id] {
                    result.append(existing)
                    return
                }
                let kind: PhoneMedia.Kind = asset.mediaType == .video ? .video : .image
                let filename = PHAssetResource.assetResources(for: asset).first?.originalFilename
                result.append(PhoneMedia(id: id,
                    title: filename.map { ($0 as NSString).deletingPathExtension } ?? (kind == .video ? "Video" : "Photo"),
                    kind: kind, source: .photos, addedAt: asset.creationDate ?? .distantPast,
                    filename: filename, duration: kind == .video ? asset.duration : nil, assetIdentifier: asset.localIdentifier))
            }
            return Task.isCancelled ? nil : PhotoScanResult(assets: resolvedAssets, records: result)
        }
        photoScanTask = task
        Task { @MainActor in
            let result = await task.value
            guard scanGeneration == generation else { return }
            photoScanTask = nil
            if let result {
                photoFetchResult = result.assets
                photos = result.records
            }
            isScanning = false
        }
    }
    func refreshDownloads(_ records: [BrowserDownload]) {
        downloadURLs = [:]
        downloads = records.compactMap { record in
            guard record.state == "Complete", let url = record.fileURL, let kind = PhoneMedia.Kind.classify(url) else { return nil }
            let id = "download:" + record.id.uuidString
            downloadURLs[id] = url
            let values = try? url.resourceValues(forKeys: [.creationDateKey, .fileSizeKey])
            return PhoneMedia(id: id, title: url.deletingPathExtension().lastPathComponent, kind: kind,
                source: .download, addedAt: values?.creationDate ?? .distantPast, filename: url.lastPathComponent,
                bytes: values?.fileSize.map(Int64.init))
        }
    }
    func item(_ id: String) -> PhoneMedia? { items.first { $0.id == id } }
    func localURL(_ item: PhoneMedia) -> URL? {
        if item.source == .download { return downloadURLs[item.id] }
        guard item.source == .imported, let filename = item.filename, UUID(uuidString: item.id) != nil else { return nil }
        return directory.appendingPathComponent(item.id).appendingPathComponent((filename as NSString).lastPathComponent)
    }
    func importFiles(_ urls: [URL]) async {
        let directory = directory
        for source in urls {
            do {
                let item = try await Task.detached(priority: .userInitiated) {
                    guard let kind = PhoneMedia.Kind.classify(source) else { throw LibraryError.unsupported }
                    let scoped = source.startAccessingSecurityScopedResource()
                    defer { if scoped { source.stopAccessingSecurityScopedResource() } }
                    let id = UUID().uuidString
                    let folder = directory.appendingPathComponent(id, isDirectory: true)
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let name = source.lastPathComponent
                    let destination = folder.appendingPathComponent(name)
                    do {
                        var coordinationError: NSError?
                        var copyError: Error?
                        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinationError) { readable in
                            do {
                                guard try readable.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { throw LibraryError.unsupported }
                                try FileManager.default.copyItem(at: readable, to: destination)
                            } catch { copyError = error }
                        }
                        if let failure = coordinationError ?? (copyError as NSError?) { throw failure }
                        let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
                        var duration: Double?
                        if kind != .image { duration = try? await AVURLAsset(url: destination).load(.duration).seconds }
                        return PhoneMedia(id: id, title: source.deletingPathExtension().lastPathComponent,
                            kind: kind, source: .imported, addedAt: Date(), filename: name,
                            duration: duration.flatMap { $0.isFinite ? $0 : nil }, bytes: size.map(Int64.init))
                    } catch { try? FileManager.default.removeItem(at: folder); throw error }
                }.value
                imported.insert(item, at: 0)
                try save()
            } catch { self.error = "Some files couldn’t be imported. Choose video, image or audio files and check available storage." }
        }
    }
    func removeImport(_ item: PhoneMedia) {
        guard item.source == .imported, let url = localURL(item) else { return }
        do {
            if FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path) { try FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
            imported.removeAll { $0.id == item.id }
            try save()
        } catch { self.error = "Couldn’t remove the imported copy." }
    }
    private func save() throws { try JSONEncoder().encode(imported).write(to: indexURL, options: .atomic) }

    enum LibraryError: LocalizedError {
        case unavailable, unsupported, exportFailed
        var errorDescription: String? {
            switch self {
            case .unavailable: return "This item is no longer available. Check Photos access or import the file again."
            case .unsupported: return "Choose a video, image or audio file."
            case .exportFailed: return "Couldn’t prepare this media. If it’s stored in iCloud, check your connection and try again."
            }
        }
    }
    func resolve(_ item: PhoneMedia) async throws -> URL {
        if let url = localURL(item), FileManager.default.fileExists(atPath: url.path) { return url }
        guard let identifier = item.assetIdentifier,
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject else { throw LibraryError.unavailable }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("LibraryPlayback", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if item.kind == .image {
            let options = PHImageRequestOptions(); options.isNetworkAccessAllowed = true; options.version = .current
            let data: Data? = await withCheckedContinuation { continuation in
                PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in continuation.resume(returning: data) }
            }
            try Task.checkCancellation()
            guard let data else { throw LibraryError.exportFailed }
            let destination = folder.appendingPathComponent(UUID().uuidString + ".jpg")
            try await Task.detached(priority: .userInitiated) {
                guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                      let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 3840] as CFDictionary),
                      let jpeg = UIImage(cgImage: image).jpegData(compressionQuality: 0.9) else { throw LibraryError.exportFailed }
                try jpeg.write(to: destination, options: .atomic)
            }.value
            return destination
        }
        let options = PHVideoRequestOptions(); options.isNetworkAccessAllowed = true; options.version = .current
        let session: AVAssetExportSession? = await withCheckedContinuation { continuation in
            PHImageManager.default().requestExportSession(forVideo: asset, options: options, exportPreset: AVAssetExportPresetPassthrough) { session, _ in continuation.resume(returning: session) }
        }
        try Task.checkCancellation()
        guard let session else { throw LibraryError.exportFailed }
        let type: AVFileType = session.supportedFileTypes.contains(.mp4) ? .mp4 : .mov
        let destination = folder.appendingPathComponent(UUID().uuidString + (type == .mp4 ? ".mp4" : ".mov"))
        session.outputURL = destination; session.outputFileType = type
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in session.exportAsynchronously { continuation.resume() } }
        } onCancel: { session.cancelExport() }
        guard session.status == .completed else { try? FileManager.default.removeItem(at: destination); throw LibraryError.exportFailed }
        return destination
    }
}
