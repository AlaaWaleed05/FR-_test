import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiFetch } from './http';
import type { ReferenceDocument, ReferenceManifest } from './types';

/**
 * Reference-data reads (`GET /api/v1/reference/manifest` and `.../lists/{listCode}/{version}`) —
 * no authentication, no PII. Per `docs/components/reference-data.md`'s own "Back office" section:
 * fetch at app load, hold in memory, no SHA-256 verification here (that's a mobile-only,
 * offline-cache rule — the back office has no offline cache to protect and talks to the same
 * backend over HTTPS in production).
 *
 * Branch and rejection-reason code/label lists are reference data (CLAUDE.md: never hardcoded) —
 * this is the one and only place their labels are fetched from.
 */

let manifestPromise: Promise<ReferenceManifest> | null = null;
const documentCache = new Map<string, Promise<ReferenceDocument>>();

function fetchManifest(): Promise<ReferenceManifest> {
  manifestPromise ??= apiFetch<ReferenceManifest>('/reference/manifest').catch((error: unknown) => {
    // A rejected promise must not stay memoized -- a transient failure (a 500, a dropped
    // connection) would otherwise be re-thrown forever, with no way to recover short of a full
    // page reload. Found under review.
    manifestPromise = null;
    throw error;
  });
  return manifestPromise;
}

function fetchDocument(listCode: string, version: number): Promise<ReferenceDocument> {
  const key = `${listCode}/${version}`;
  let cached = documentCache.get(key);
  if (!cached) {
    cached = apiFetch<ReferenceDocument>(`/reference/lists/${listCode}/${version}`).catch((error: unknown) => {
      documentCache.delete(key);
      throw error;
    });
    documentCache.set(key, cached);
  }
  return cached;
}

async function loadCurrentList(listCode: string): Promise<ReferenceDocument> {
  const manifest = await fetchManifest();
  const entry = manifest.lists.find((l) => l.listCode === listCode);
  if (!entry) {
    throw new Error(`reference list not found in manifest: ${listCode}`);
  }
  return fetchDocument(listCode, entry.version);
}

export interface UseReferenceListResult {
  items: ReferenceDocument['items'];
  loading: boolean;
  error: Error | null;
  /** Re-attempts the fetch -- meaningful because a failure is no longer memoized forever. */
  retry: () => void;
}

/** Fetches a reference list's current version once, sorted by `sortOrdinal` (server-computed
 * display order — never re-sorted alphabetically client-side, per AD-006). */
export function useReferenceList(listCode: string): UseReferenceListResult {
  const [state, setState] = useState<{ items: ReferenceDocument['items']; loading: boolean; error: Error | null }>({
    items: [],
    loading: true,
    error: null,
  });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState({ items: [], loading: true, error: null });
    loadCurrentList(listCode)
      .then((doc) => {
        if (cancelled) return;
        const sorted = [...doc.items].sort((a, b) => a.sortOrdinal - b.sortOrdinal);
        setState({ items: sorted, loading: false, error: null });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setState({ items: [], loading: false, error: error instanceof Error ? error : new Error(String(error)) });
      });
    return () => {
      cancelled = true;
    };
  }, [listCode, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  return { ...state, retry };
}

/**
 * `itemCode -> labelAr` for a reference list's CURRENT published version — used to resolve
 * customer-data reference codes (occupation, admin_division, country, income_source,
 * education_level) to labels on the single profile view. Same current-version approach
 * `ProfileListPage` already uses for branch/rejection-reason; a profile's data may have been
 * recorded against an older pinned version (BL-025's already-tracked, accepted limitation — this
 * response carries no per-field version to resolve against exactly).
 */
export function useReferenceLabelMap(
  listCode: string,
): { map: Map<string, string>; loading: boolean; error: Error | null; retry: () => void } {
  const { items, loading, error, retry } = useReferenceList(listCode);
  const map = useMemo(() => {
    const m = new Map<string, string>();
    for (const item of items) m.set(item.itemCode, item.labelAr);
    return m;
  }, [items]);
  return { map, loading, error, retry };
}

/**
 * `alpha-3 code -> labelAr` for the `country` list, resolving the two MRZ-sourced country fields
 * the profile screen shows: field 4 «الجنسية» (`scanResult.nationality`) and field 48
 * «بلد الإصدار» (`scanResult.issuingCountry`).
 *
 * **This is what closed BL-157.** That item was filed on the premise that "no alpha-3 to alpha-2
 * mapping exists anywhere in the repo", and therefore that those two fields could only ever show
 * the raw code where the approved artboard draws «السودان». The premise was wrong:
 * `V0022__seed_country.sql` seeds `extra` as `{"alpha3":"SDN"}` for all 249 rows, the reference
 * document carries `extra` to the browser untouched, and `useReferenceList` already returns it.
 * So the mapping is SERVER-SUPPLIED and VERSION-CHECKED like every other reference list, which
 * is exactly what CLAUDE.md's "reference lists are never hardcoded" rule requires -- building it
 * here hardcodes nothing.
 *
 * **A miss is expected and must fall back to the code, not to a dash.** An MRZ issuer/nationality
 * is an ICAO alpha-3, which overlaps ISO 3166 alpha-3 but is not the same set: `XXA`/`XXB`/`XXC`
 * for stateless and refugee travel documents, `GBD`/`GBN`/`GBO`/`GBP`/`GBS` for British national
 * categories, `RKS` for Kosovo, `D` for Germany. None of those has an ISO row to resolve to, and
 * showing a dash would destroy information the document actually carried. Callers render the raw
 * code on a miss, which is the behaviour BL-157 settled for.
 *
 * Only `extra` objects that actually carry a string `alpha3` contribute -- `extra` is `unknown`
 * on the wire (it is a free-form jsonb column) and every other list leaves it null.
 */
export function useAlpha3LabelMap(): { map: Map<string, string>; loading: boolean; error: Error | null; retry: () => void } {
  const { items, loading, error, retry } = useReferenceList('country');
  const map = useMemo(() => {
    const m = new Map<string, string>();
    for (const item of items) {
      const extra = item.extra;
      if (extra && typeof extra === 'object' && 'alpha3' in extra) {
        const alpha3 = (extra as { alpha3?: unknown }).alpha3;
        if (typeof alpha3 === 'string' && alpha3 !== '') {
          m.set(alpha3.toUpperCase(), item.labelAr);
        }
      }
    }
    return m;
  }, [items]);
  return { map, loading, error, retry };
}
