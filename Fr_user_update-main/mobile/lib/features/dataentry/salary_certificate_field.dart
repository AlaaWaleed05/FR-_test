import 'dart:io';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:image_picker/image_picker.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/images/image_downscale.dart';
import '../../core/text/ltr_value.dart';

/// Stage 6's optional salary/income certificate (field 50, docs/journeys/field-provenance.md) —
/// "Camera capture or file selection," gates nothing, must never block completion.
///
/// **The upload is wired as of S8-14 (BL-105).** The comment that stood here until then said "no
/// backend endpoint exists to upload this to". That was true when written and false from S4-06
/// onward, when `POST /api/v1/salary-certificate` shipped — and because the comment was never
/// revisited, three later sessions read it and moved on while the customer was shown «تم إرفاق»
/// for a file that was deleted, unsent, with the rest of local state.
///
/// **The confirmation is now earned, not assumed.** A stored path only proves a file is on this
/// handset; [uploaded] is what proves the bank has it, and only that renders «تم إرفاق». A file
/// that has not made it yet says so plainly and offers a retry. Nothing here blocks Next: the
/// certificate gates nothing, and a failed upload that stopped the journey would be a worse
/// defect than the one this replaces.
///
/// Images are downscaled on-device before storage (customer.md: "a modern phone camera produces
/// files far larger than a legible certificate needs"); a picked PDF is stored byte-identical,
/// since the `image` package has nothing to downscale in a PDF. customer.md's stated "10 MB"
/// ceiling is enforced on the ORIGINAL picked bytes, before any downscaling — a legible
/// certificate should never approach it, so this is a rejection of an oversized/wrong file, not a
/// normal path.
class SalaryCertificateField extends StatelessWidget {
  const SalaryCertificateField({
    super.key,
    required this.path,
    required this.onChanged,
    this.uploaded = false,
    this.uploading = false,
    this.onRetryUpload,
  });

  /// The stored local file path, or `null` if nothing has been captured yet.
  final String? path;
  final ValueChanged<String?> onChanged;

  /// Whether the backend has ACCEPTED the file at [path]. The only thing that earns «تم إرفاق».
  final bool uploaded;

  /// Whether an upload of [path] is in flight right now.
  final bool uploading;

  /// Retries the upload of an already-picked file. Null when there is nothing to retry.
  final VoidCallback? onRetryUpload;

  static const _jpegQuality = 85;

  Future<void> _captureFromCamera(BuildContext context) async {
    final ImageInfo? picked;
    try {
      final xFile = await ImagePicker().pickImage(source: ImageSource.camera);
      picked = xFile == null
          ? null
          : ImageInfo(await xFile.readAsBytes(), xFile.name);
    } on PlatformException catch (_) {
      if (!context.mounted) return;
      _showError(context, 'تعذر فتح الكاميرا. تأكد من السماح للتطبيق باستخدام الكاميرا من إعدادات الهاتف.');
      return;
    }
    if (picked == null) return;
    if (!context.mounted) return;
    if (!_checkSize(context, picked.bytes)) return;
    final savedPath = await _downscaleAndStore(picked.bytes);
    if (savedPath == null) {
      if (context.mounted) _showError(context, 'تعذر قراءة الصورة الملتقطة.');
      return;
    }
    await _deleteExistingFile(keeping: savedPath);
    onChanged(savedPath);
  }

  Future<void> _pickFile(BuildContext context) async {
    final PlatformFile? picked;
    try {
      picked = await FilePicker.pickFile(
        type: FileType.custom,
        allowedExtensions: ['jpg', 'jpeg', 'png', 'pdf'],
      );
    } on PlatformException catch (_) {
      if (!context.mounted) return;
      _showError(context, 'تعذر فتح الملفات. أعد المحاولة.');
      return;
    }
    if (picked == null) return;
    final bytes = await picked.readAsBytes();
    if (!context.mounted) return;
    if (!_checkSize(context, bytes)) return;
    final extension = p.extension(picked.name).toLowerCase();
    final savedPath = extension == '.pdf'
        ? await _storeBytes(bytes, 'salary_certificate.pdf')
        : await _downscaleAndStore(bytes);
    if (savedPath == null) {
      if (context.mounted) _showError(context, 'تعذر قراءة الملف المحدد.');
      return;
    }
    await _deleteExistingFile(keeping: savedPath);
    onChanged(savedPath);
  }

  bool _checkSize(BuildContext context, Uint8List bytes) {
    if (bytes.length <= maxPickedImageBytes) return true;
    _showError(context, 'حجم الملف يتجاوز الحد الأقصى (10 ميغابايت).');
    return false;
  }

  void _showError(BuildContext context, String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  /// Deletes whatever file [path] currently points at, unless it is the same file as [keeping] —
  /// found under review, S5-05: switching between an image and a PDF attachment writes under a
  /// DIFFERENT fixed filename (`salary_certificate.jpg` vs `.pdf`), so without this the
  /// previously-attached file (real, if synthetic-in-testing, PII) is orphaned on disk
  /// indefinitely — never referenced by [DataEntryDraft.salaryCertificatePath] again, but never
  /// removed either.
  ///
  /// **Called only AFTER [keeping] has been successfully written** — found under review, S5-05,
  /// second pass: the original ordering deleted the old file BEFORE attempting the new one, so a
  /// decode failure on the new bytes left `onChanged` never called (the draft still pointing at
  /// the old path) while that old file was already gone — the UI then claimed an attachment that
  /// no longer existed. Deleting only once the replacement is confirmed on disk means a failed
  /// pick always leaves the PREVIOUS attachment intact, never a dangling reference.
  Future<void> _deleteExistingFile({required String keeping}) async {
    final existing = path;
    if (existing == null || existing == keeping) return;
    final file = File(existing);
    if (await file.exists()) await file.delete();
  }

  /// Downscales via the shared [downscaleToJpeg] (S5-08 lifted it into `core/images/` so Stage 11's
  /// signature upload shares one implementation with this one) and stores the result.
  Future<String?> _downscaleAndStore(Uint8List bytes) async {
    final jpegBytes = downscaleToJpeg(bytes, quality: _jpegQuality);
    if (jpegBytes == null) return null;
    return _storeBytes(jpegBytes, 'salary_certificate.jpg');
  }

  Future<String> _storeBytes(List<int> bytes, String fileName) async {
    final dir = await getApplicationSupportDirectory();
    final file = File(p.join(dir.path, fileName));
    await file.writeAsBytes(bytes, flush: true);
    return file.path;
  }

  /// The three honest states a picked file can be in. Only the middle one claims an attachment.
  ///
  /// The filename is isolated with FSI/PDI in every one of them: this is the ONE place in the app
  /// where a Latin-reading value is interpolated INTO an Arabic run rather than standing alone in
  /// its own widget, so without isolation a name like `payslip-2026.pdf` reorders against the
  /// Arabic around it.
  Widget _status(BuildContext context) {
    final name = LtrValue.isolate(p.basename(path!));
    if (uploading) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 8),
            Expanded(child: Text('جارٍ إرفاق: $name')),
          ],
        ),
      );
    }
    if (uploaded) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Text('تم إرفاق: $name'),
      );
    }
    // Picked but not accepted. Says so, and offers the retry — never silently claims success.
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'لم يتم إرفاق $name بعد. يمكنك المتابعة، وسنحاول الإرفاق مرة أخرى.',
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
          if (onRetryUpload != null)
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton(
                onPressed: onRetryUpload,
                child: const Text('إعادة محاولة الإرفاق'),
              ),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'شهادة الدخل / المرتب (اختياري)',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        if (path != null) _status(context),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                // Disabled while an upload is in flight — found by `@agent-reviewer` on the
                // S8-14 diff. Two overlapping picks let the FIRST upload's success stamp the
                // draft while the screen names the SECOND file, which is the BL-105 false
                // confirmation rebuilt out of a race. `Stage6Screen._uploadCertificate` also
                // discards a stale result; this stops the race being easy to start.
                onPressed: uploading ? null : () => _captureFromCamera(context),
                icon: const Icon(Icons.camera_alt_outlined),
                label: const Text('التقاط صورة'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: uploading ? null : () => _pickFile(context),
                icon: const Icon(Icons.attach_file),
                label: const Text('اختيار ملف'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// Tiny holder so the camera-capture path can carry both the read bytes and a display name
/// through one `PlatformException`-guarded block, mirroring `PlatformFile`'s own shape closely
/// enough that `_checkSize`/naming logic doesn't need a second variant.
class ImageInfo {
  const ImageInfo(this.bytes, this.name);

  final Uint8List bytes;
  final String name;
}
