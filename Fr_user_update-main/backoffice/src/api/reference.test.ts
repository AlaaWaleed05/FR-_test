import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReferenceDocument, ReferenceManifest } from './types';

const manifest: ReferenceManifest = {
  catalogHash: 'h',
  generatedAt: '2026-01-01T00:00:00Z',
  verifiableChannels: ['sms'],
  lists: [
    {
      listCode: 'branch',
      version: 1,
      itemCount: 2,
      contentHash: 'ch',
      isHierarchical: false,
      rootItemCode: null,
      rootCountryVersion: null,
      publishedAt: '2026-01-01T00:00:00Z',
      documentPath: '/api/v1/reference/lists/branch/1',
    },
  ],
};

const document: ReferenceDocument = {
  listCode: 'branch',
  version: 1,
  itemCount: 2,
  nameAr: 'الفروع',
  nameEn: 'Branches',
  isHierarchical: false,
  rootItemCode: null,
  items: [
    { itemCode: '2', parentCode: null, labelAr: 'ب', labelEn: null, searchAr: 'ب', searchEn: null, sortOrdinal: 2, isActive: true, extra: null },
    { itemCode: '1', parentCode: null, labelAr: 'أ', labelEn: null, searchAr: 'ا', searchEn: null, sortOrdinal: 1, isActive: true, extra: null },
  ],
};

// reference.ts memoizes the manifest/document promises at module scope -- each test needs a
// genuinely fresh module instance (vi.resetModules + a dynamic re-import), or the second test
// would silently reuse the first test's cached, already-resolved manifest.
describe('useReferenceList', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('fetches the manifest then the current version, sorted by sortOrdinal', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/reference/manifest')) {
        return new Response(JSON.stringify(manifest), { status: 200 });
      }
      return new Response(JSON.stringify(document), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { useReferenceList } = await import('./reference');

    const { result } = renderHook(() => useReferenceList('branch'));

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBeNull();
    expect(result.current.items.map((i) => i.itemCode)).toEqual(['1', '2']);
  });

  it('does not update state after unmounting mid-fetch', async () => {
    let resolveManifest!: (r: Response) => void;
    const fetchMock = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolveManifest = resolve;
        }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const { useReferenceList } = await import('./reference');

    const { unmount } = renderHook(() => useReferenceList('branch'));
    unmount();
    resolveManifest(new Response(JSON.stringify(manifest), { status: 200 }));

    // No assertion beyond "this resolves without React warning about a state update on an
    // unmounted component" -- the cancelled-guard branch is what prevents that.
    await new Promise((r) => setTimeout(r, 10));
  });

  it('retries and recovers after the list DOCUMENT (not the manifest) fails once', async () => {
    // Distinct from the manifest-cache-clearing path already covered elsewhere: this exercises
    // documentCache.delete(key) specifically -- the manifest fetch succeeds throughout, only the
    // per-list document 500s once, and retry() must still recover.
    let documentCallCount = 0;
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/reference/manifest')) {
        return new Response(JSON.stringify(manifest), { status: 200 });
      }
      documentCallCount += 1;
      if (documentCallCount === 1) {
        return new Response('boom', { status: 500 });
      }
      return new Response(JSON.stringify(document), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);
    const { useReferenceList } = await import('./reference');

    const { result } = renderHook(() => useReferenceList('branch'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.items).toEqual([]);

    act(() => {
      result.current.retry();
    });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeNull();
    expect(result.current.items.map((i) => i.itemCode)).toEqual(['1', '2']);
    expect(documentCallCount).toBe(2);
  });

  it('surfaces an error when the list is not in the manifest', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ ...manifest, lists: [] }), { status: 200 })),
    );
    const { useReferenceList } = await import('./reference');

    const { result } = renderHook(() => useReferenceList('branch'));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeInstanceOf(Error);
  });
});

/**
 * The alpha-3 index over the `country` list — what closed BL-157.
 *
 * BL-157 was filed on the premise that no alpha-3-to-alpha-2 mapping existed anywhere in the
 * repo, and therefore that the two MRZ country fields could only ever show the raw code. The
 * premise was wrong: `V0022__seed_country.sql` seeds `extra` as `{"alpha3":"SDN"}` for all 249
 * rows and `ReferenceDocumentItem.extra` already carries it to the browser. Nothing is hardcoded
 * here — the map is built from the same server-supplied, version-checked list every other country
 * field resolves through.
 */
describe('useAlpha3LabelMap', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const countryManifest: ReferenceManifest = {
    ...manifest,
    lists: [{ ...manifest.lists[0], listCode: 'country', documentPath: '/api/v1/reference/lists/country/1' }],
  };

  function countryDocument(items: ReferenceDocument['items']): ReferenceDocument {
    return { ...document, listCode: 'country', itemCount: items.length, items };
  }

  function stub(items: ReferenceDocument['items']) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url.includes('/reference/manifest')
          ? new Response(JSON.stringify(countryManifest), { status: 200 })
          : new Response(JSON.stringify(countryDocument(items)), { status: 200 }),
      ),
    );
  }

  function item(itemCode: string, labelAr: string, extra: unknown): ReferenceDocument['items'][number] {
    return { itemCode, parentCode: null, labelAr, labelEn: null, searchAr: null, searchEn: null, sortOrdinal: 1, isActive: true, extra };
  }

  it('keys the map on alpha-3 while the list itself is keyed alpha-2', async () => {
    stub([item('SD', 'السودان', { alpha3: 'SDN' }), item('EG', 'مصر', { alpha3: 'EGY' })]);
    const { useAlpha3LabelMap } = await import('./reference');

    const { result } = renderHook(() => useAlpha3LabelMap());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.map.get('SDN')).toBe('السودان');
    expect(result.current.map.get('EGY')).toBe('مصر');
    // The alpha-2 code must NOT resolve: an MRZ never carries one, and accepting it would hide a
    // caller passing the wrong field.
    expect(result.current.map.get('SD')).toBeUndefined();
  });

  it('matches case-insensitively, since an MRZ is upper case and a column may not be', async () => {
    stub([item('SD', 'السودان', { alpha3: 'sdn' })]);
    const { useAlpha3LabelMap } = await import('./reference');

    const { result } = renderHook(() => useAlpha3LabelMap());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.map.get('SDN')).toBe('السودان');
  });

  it('ignores rows whose extra carries no usable alpha3', async () => {
    // `extra` is a free-form jsonb column typed `unknown` on the wire, and every other reference
    // list leaves it null. A row without one contributes nothing rather than throwing.
    stub([
      item('SD', 'السودان', { alpha3: 'SDN' }),
      item('XX', 'بلا', null),
      item('YY', 'بلا-2', { alpha3: 42 }),
      item('ZZ', 'بلا-3', { alpha3: '' }),
      item('WW', 'بلا-4', 'not-an-object'),
    ]);
    const { useAlpha3LabelMap } = await import('./reference');

    const { result } = renderHook(() => useAlpha3LabelMap());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.map.size).toBe(1);
    expect(result.current.map.get('SDN')).toBe('السودان');
  });

  it('resolves nothing for an ICAO code with no ISO row, so the caller can show it raw', async () => {
    stub([item('SD', 'السودان', { alpha3: 'SDN' })]);
    const { useAlpha3LabelMap } = await import('./reference');

    const { result } = renderHook(() => useAlpha3LabelMap());
    await waitFor(() => expect(result.current.loading).toBe(false));

    // XXA (stateless), GBD (British overseas) and RKS (Kosovo) are real MRZ values that are not
    // ISO 3166. A miss must be a miss -- the screen shows the code as received rather than a dash.
    for (const icao of ['XXA', 'GBD', 'RKS']) {
      expect(result.current.map.get(icao)).toBeUndefined();
    }
  });

  it('surfaces the list failure rather than silently returning an empty map', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })));
    const { useAlpha3LabelMap } = await import('./reference');

    const { result } = renderHook(() => useAlpha3LabelMap());
    await waitFor(() => expect(result.current.loading).toBe(false));

    // The profile screen folds this into its reference-lists warning; an empty map with no error
    // would degrade fields 4 and 48 to raw codes with nothing saying why.
    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.map.size).toBe(0);
  });
});
