import { describe, expect, it } from 'vitest';
import type { ArtifactRefView } from '../api/types';
import {
  TILE_CAPTIONS_AR,
  TILE_SOURCES_AR,
  VIEWABLE_KINDS,
  artifactImageSrc,
  isPdf,
  selectTiles,
  tileAccessibleName,
} from './artifactTiles';

function artifact(kind: string, artifactRefId: string, overrides: Partial<ArtifactRefView> = {}): ArtifactRefView {
  return {
    artifactRefId,
    kind,
    label: kind,
    storageKey: null,
    contentType: 'image/jpeg',
    byteSize: 1000,
    sha256Hex: 'a'.repeat(64),
    ...overrides,
  };
}

describe('artifactTiles', () => {
  // Pins the CLIENT's list only. Nothing here reaches OperatorImagePolicy.viewableKinds(), and no
  // gate in any tier compares the two -- see the drift warning on VIEWABLE_KINDS itself.
  it('pins the six viewable kinds in the approved order, income evidence last', () => {
    // REORDERED at S9-02 to the approved artboard: the two portraits first, so the face an
    // operator is comparing leads the row. `doc_back` is still absent -- BL-159, six not seven.
    expect(VIEWABLE_KINDS).toEqual([
      'portrait_registry',
      'portrait_uqudo',
      'doc_front',
      'face_audit_trail',
      'signature',
      'salary_certificate',
    ]);
  });

  it('captions every viewable kind in Arabic, and sources it in Arabic too', () => {
    for (const kind of VIEWABLE_KINDS) {
      expect(TILE_CAPTIONS_AR[kind]).toMatch(/[؀-ۿ]/);
      expect(TILE_SOURCES_AR(kind, 'SDN_ID')).toMatch(/[؀-ۿ]/);
    }
  });

  it('sources the two document-derived tiles from the document ACTUALLY scanned', () => {
    // The artboard's fixture is a national-ID customer and draws «البطاقة القومية» under both.
    // Hardcoding that string would have mislabelled every passport profile, which is why this
    // is a function of documentType rather than a second Record.
    expect(TILE_SOURCES_AR('portrait_uqudo', 'SDN_ID')).toBe('البطاقة القومية');
    expect(TILE_SOURCES_AR('doc_front', 'SDN_ID')).toBe('البطاقة القومية');
    expect(TILE_SOURCES_AR('portrait_uqudo', 'PASSPORT')).toBe('جواز سفر');
    expect(TILE_SOURCES_AR('doc_front', 'PASSPORT')).toBe('جواز سفر');
  });

  it('leaves the four document-independent sources alone whatever was scanned', () => {
    for (const documentType of ['SDN_ID', 'PASSPORT', null]) {
      expect(TILE_SOURCES_AR('portrait_registry', documentType)).toBe('السجل المدني');
      expect(TILE_SOURCES_AR('face_audit_trail', documentType)).toBe('التحقق الحي');
      expect(TILE_SOURCES_AR('signature', documentType)).toBe('وقّعه العميل');
      expect(TILE_SOURCES_AR('salary_certificate', documentType)).toBe('أرفقها العميل');
    }
  });

  it('claims no document before the scan lands', () => {
    expect(TILE_SOURCES_AR('doc_front', null)).toBe('—');
    expect(TILE_SOURCES_AR('portrait_uqudo', null)).toBe('—');
  });

  it('disambiguates the two tiles that share a caption', () => {
    // The whole reason tileAccessibleName exists: both portraits are «الصورة الشخصية», so a
    // caption alone would announce two identical tiles to a screen reader.
    expect(TILE_CAPTIONS_AR.portrait_registry).toBe(TILE_CAPTIONS_AR.portrait_uqudo);
    expect(tileAccessibleName('portrait_registry', 'SDN_ID')).not.toBe(
      tileAccessibleName('portrait_uqudo', 'SDN_ID'),
    );
    expect(tileAccessibleName('portrait_registry', 'SDN_ID')).toBe('الصورة الشخصية — السجل المدني');
  });

  it('addresses bytes at the operator image endpoint, root-relative and same-origin', () => {
    expect(artifactImageSrc('p-1', 'a-1')).toBe('/api/v1/operator/profiles/p-1/artifacts/a-1');
  });

  it('escapes both path segments', () => {
    expect(artifactImageSrc('p/1', 'a 1')).toBe('/api/v1/operator/profiles/p%2F1/artifacts/a%201');
  });

  it('returns all six tiles even when the profile carries nothing', () => {
    const tiles = selectTiles([]);

    expect(tiles.map((t) => t.kind)).toEqual([...VIEWABLE_KINDS]);
    expect(tiles.every((t) => t.artifact === null)).toBe(true);
  });

  it('drops every kind the endpoint would refuse', () => {
    const tiles = selectTiles([
      artifact('doc_back', 'back-1'),
      // The byte-less frame: declared image/jpeg at 1,726,304 bytes with a NULL body, and the
      // largest row the listing returns. BL-075 requirement (a) exists to keep it off the page.
      artifact('doc_front_frame', 'frame-1', { byteSize: 1_726_304 }),
      artifact('doc_back_frame', 'frame-2', { byteSize: 1_400_000 }),
    ]);

    expect(tiles.every((t) => t.artifact === null)).toBe(true);
  });

  it('takes the LAST committed row of a kind, not the first', () => {
    // `ORDER BY ar.created_at` ascending, and `UNIQUE (cycle_id, kind)` lets one profile hold
    // several rows of a kind across identity cycles -- so the first is the SUPERSEDED scan.
    const tiles = selectTiles([artifact('doc_front', 'superseded'), artifact('doc_front', 'current')]);

    expect(tiles.find((t) => t.kind === 'doc_front')?.artifact?.artifactRefId).toBe('current');
  });

  it('gives the salary certificate a tile of its own (BL-136)', () => {
    const tiles = selectTiles([artifact('salary_certificate', 'salary-1')]);

    expect(tiles.find((t) => t.kind === 'salary_certificate')?.artifact?.artifactRefId).toBe('salary-1');
  });

  it('recognises a PDF certificate, and only by its declared type', () => {
    expect(isPdf(artifact('salary_certificate', 's1', { contentType: 'application/pdf' }))).toBe(true);
    expect(isPdf(artifact('salary_certificate', 's2', { contentType: 'APPLICATION/PDF' }))).toBe(true);
    expect(isPdf(artifact('salary_certificate', 's3', { contentType: 'application/pdf; x=1' }))).toBe(true);
    expect(isPdf(artifact('salary_certificate', 's4', { contentType: 'image/jpeg' }))).toBe(false);
  });

  it('pairs each present artifact with its own tile', () => {
    const tiles = selectTiles([artifact('signature', 'sig-1'), artifact('portrait_registry', 'cr-1')]);

    expect(tiles.find((t) => t.kind === 'signature')?.artifact?.artifactRefId).toBe('sig-1');
    expect(tiles.find((t) => t.kind === 'portrait_registry')?.artifact?.artifactRefId).toBe('cr-1');
    expect(tiles.find((t) => t.kind === 'doc_front')?.artifact).toBeNull();
  });
});
