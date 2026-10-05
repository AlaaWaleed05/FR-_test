import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ArtifactContactSheet from './ArtifactContactSheet';
import type { ArtifactRefView } from '../api/types';

const PROFILE = '99999999-9999-4999-8999-999999999999';

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

/** The five identity kinds. The salary certificate is added per test, since its TYPE is the point. */
const ALL_FIVE = [
  artifact('doc_front', 'doc-front-1'),
  artifact('portrait_uqudo', 'uqudo-1'),
  artifact('portrait_registry', 'cr-1'),
  artifact('face_audit_trail', 'liveness-1'),
  artifact('signature', 'sig-1', { contentType: 'image/png' }),
];

const PDF_CERTIFICATE = artifact('salary_certificate', 'salary-pdf-1', {
  contentType: 'application/pdf',
  byteSize: 900_000,
});

/**
 * Real `<img>` tags only. `getAllByRole('img')` would also match every Ant Design icon on the page
 * (each is a `<span role="img">`), which would make a count assertion mean something other than
 * "this many images are being fetched".
 */
function images(): HTMLImageElement[] {
  return Array.from(document.querySelectorAll('img'));
}

function srcs(): string[] {
  return images().map((img) => img.getAttribute('src') ?? '');
}

describe('ArtifactContactSheet', () => {
  it('renders the six approved tiles, each captioned with its name and its source', () => {
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[...ALL_FIVE, PDF_CERTIFICATE]} />);

    // «الصورة الشخصية» appears TWICE by design -- the registry portrait and the document
    // portrait are the same thing from two sources, and the second caption line is what tells
    // them apart. getAllByText, therefore, with a count that pins the duplication deliberately.
    expect(screen.getAllByText('الصورة الشخصية')).toHaveLength(2);
    for (const caption of ['وجه وثيقة الهوية', 'صورة التحقق الحي', 'التوقيع', 'شهادة المرتب']) {
      expect(screen.getByText(caption)).toBeInTheDocument();
    }
    // The source lines. «البطاقة القومية» twice: the document portrait and the document front.
    expect(screen.getByText('السجل المدني')).toBeInTheDocument();
    expect(screen.getAllByText('البطاقة القومية')).toHaveLength(2);
    expect(screen.getByText('التحقق الحي')).toBeInTheDocument();
    expect(screen.getByText('وقّعه العميل')).toBeInTheDocument();
    expect(screen.getByText('أرفقها العميل')).toBeInTheDocument();
  });

  it('renders the approved ORDER, portraits first and income evidence last', () => {
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[...ALL_FIVE, PDF_CERTIFICATE]} />);

    // Read off the rendered <img alt>s plus the PDF tile's own label, in document order --
    // asserting the ORDER rather than mere presence, which is what changed at S9-02.
    const names = Array.from(document.querySelectorAll('img[alt], button[aria-label]')).map(
      (el) => el.getAttribute('alt') ?? el.getAttribute('aria-label') ?? '',
    );
    expect(names).toEqual([
      'الصورة الشخصية — السجل المدني',
      'الصورة الشخصية — البطاقة القومية',
      'وجه وثيقة الهوية — البطاقة القومية',
      'صورة التحقق الحي — التحقق الحي',
      'التوقيع — وقّعه العميل',
      'شهادة المرتب — أرفقها العميل',
    ]);
  });

  it('names the document actually scanned, not the artboard fixture', () => {
    render(<ArtifactContactSheet documentType="PASSPORT" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={ALL_FIVE} />);

    // The whole reason `documentType` became a prop. A passport customer must not be told the
    // bank holds their national ID.
    expect(screen.getAllByText('جواز سفر')).toHaveLength(2);
    expect(screen.queryByText('البطاقة القومية')).not.toBeInTheDocument();
  });

  it('says nothing about a document before the scan lands', () => {
    render(<ArtifactContactSheet documentType={null} profileId={PROFILE} salaryCertificateState="NOT_REACHED" artifacts={[]} />);

    expect(screen.queryByText('البطاقة القومية')).not.toBeInTheDocument();
    expect(screen.queryByText('جواز سفر')).not.toBeInTheDocument();
    // The tiles are still all six, still in order, still captioned -- an operator needs to know
    // what is missing, not merely not see it.
    expect(screen.getAllByText('الصورة الشخصية')).toHaveLength(2);
  });

  it('points every tile at the operator image endpoint for its own artifact', () => {
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={ALL_FIVE} />);

    for (const id of ['doc-front-1', 'uqudo-1', 'cr-1', 'liveness-1', 'sig-1']) {
      expect(srcs()).toContain(`/api/v1/operator/profiles/${PROFILE}/artifacts/${id}`);
    }
  });

  it('NEVER gives a non-viewable kind an <img src>, however the listing returns it', () => {
    // The profile-detail listing is not filtered server-side: it still returns an artifactRefId for
    // these four, and the endpoint 404s every one. The 1.7 MB byte-less capture frame is the
    // failure BL-075 requirement (a) exists to prevent -- it is the LARGEST row the listing offers.
    render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE} salaryCertificateState="DECLINED"
        artifacts={[
          ...ALL_FIVE,
          artifact('doc_back', 'back-1'),
          artifact('doc_front_frame', 'frame-front', { byteSize: 1_726_304 }),
          artifact('doc_back_frame', 'frame-back', { byteSize: 1_400_000 }),
        ]}
      />,
    );

    const rendered = srcs().join(' ');
    for (const refused of ['back-1', 'frame-front', 'frame-back']) {
      expect(rendered).not.toContain(refused);
    }
    expect(images()).toHaveLength(5);
  });

  it('links the latest row when a re-scanned profile carries several of one kind', () => {
    render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE} salaryCertificateState="DECLINED"
        artifacts={[artifact('doc_front', 'superseded-scan'), artifact('doc_front', 'current-scan')]}
      />,
    );

    expect(srcs().join(' ')).toContain('current-scan');
    expect(srcs().join(' ')).not.toContain('superseded-scan');
  });

  it('renders a kind the profile has not reached as an empty tile with no image at all', () => {
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[artifact('doc_front', 'doc-front-1')]} />);

    // Four identity kinds are missing, and the certificate has its own wording below.
    expect(screen.getAllByText('لم يصل الملف إلى هذه المرحلة بعد')).toHaveLength(4);
    expect(images()).toHaveLength(1);
  });

  it('says an absent salary certificate was not attached, not that the stage is pending', () => {
    // The certificate is optional and gates nothing, so on a submitted profile its absence means
    // the customer declined -- a finished state. "Has not reached this stage yet" would be a claim
    // about a future that never arrives.
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={ALL_FIVE} />);

    expect(screen.getByText('لم يُرفق (اختياري)')).toBeInTheDocument();
    expect(screen.queryByText('لم يصل الملف إلى هذه المرحلة بعد')).not.toBeInTheDocument();
  });

  it('distinguishes a failed certificate upload from a decline, on identical artifacts', () => {
    // BL-122, stated as the one assertion that closes it. Both renders receive the SAME artifact
    // list -- the five identity images, no certificate row -- because that is precisely the
    // problem: declined and upload-failed were byte-identical at rest. Only the resolved state
    // differs, and the operator must be told two different things.
    const { unmount } = render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE}
        salaryCertificateState="ATTACH_FAILED"
        artifacts={ALL_FIVE}
      />,
    );

    expect(screen.getByText('أرفق العميل شهادة لم تصل إلى البنك')).toBeInTheDocument();
    expect(screen.queryByText('لم يُرفق (اختياري)')).not.toBeInTheDocument();
    unmount();

    render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE}
        salaryCertificateState="DECLINED"
        artifacts={ALL_FIVE}
      />,
    );

    expect(screen.getByText('لم يُرفق (اختياري)')).toBeInTheDocument();
    expect(screen.queryByText('أرفق العميل شهادة لم تصل إلى البنك')).not.toBeInTheDocument();
  });

  it('says the stage was never reached when Stage 6 never arrived', () => {
    // The third absence, and the reason "declined" is derived rather than asserted: a customer who
    // never reached Stage 6 was never asked, and must not be reported as having declined.
    render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE}
        salaryCertificateState="NOT_REACHED"
        artifacts={ALL_FIVE}
      />,
    );

    expect(screen.getAllByText('لم يصل الملف إلى هذه المرحلة بعد').length).toBeGreaterThan(0);
    expect(screen.queryByText('لم يُرفق (اختياري)')).not.toBeInTheDocument();
  });

  it('never states a certificate outcome when the bank actually holds one', () => {
    // PRESENT is in the copy map only to keep it total; a real certificate renders as a tile and
    // must never fall through to absence wording.
    render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE}
        salaryCertificateState="PRESENT"
        artifacts={[...ALL_FIVE, PDF_CERTIFICATE]}
      />,
    );

    expect(screen.queryByText('لم يُرفق (اختياري)')).not.toBeInTheDocument();
    expect(screen.queryByText('أرفق العميل شهادة لم تصل إلى البنك')).not.toBeInTheDocument();
  });

  it('never gives an identity kind a document tile, however its content type column reads', () => {
    // The threat this design names: `content_type` is DECLARED and can lie. A doc_front claiming
    // application/pdf must fall through to the <img> path, where failure is visible, rather than
    // become a PDF tile framing a URL the server 404s into a blank modal.
    render(
      <ArtifactContactSheet
        documentType="SDN_ID"
        profileId={PROFILE} salaryCertificateState="DECLINED"
        artifacts={[artifact('doc_front', 'liar-1', { contentType: 'application/pdf' })]}
      />,
    );

    expect(srcs()).toContain(`/api/v1/operator/profiles/${PROFILE}/artifacts/liar-1`);
    // The document tile's own marker, which only a PDF path renders.
    expect(screen.queryByText(/PDF/)).not.toBeInTheDocument();
  });

  it('degrades a tile whose bytes do not load into a visible failure, not an empty box', () => {
    // What `portrait_registry` does under the civil-registry stub (a 32-byte ASCII string declared
    // image/jpeg), and what every tile does at once when the session has expired -- an <img> never
    // reaches the SPA's 401 handler, so this is the only place the operator is told.
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={ALL_FIVE} />);

    const registryTile = screen.getByAltText('الصورة الشخصية — السجل المدني');
    fireEvent.error(registryTile);

    expect(screen.getByText('تعذّر عرض هذه الصورة')).toBeInTheDocument();
    expect(screen.queryByAltText('السجل المدني')).not.toBeInTheDocument();
    // The other four are untouched -- failure is per artifact, not per sheet.
    expect(images()).toHaveLength(4);
  });

  it('renders an IMAGE salary certificate as an ordinary tile', () => {
    const jpegCertificate = artifact('salary_certificate', 'salary-jpeg-1');
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[...ALL_FIVE, jpegCertificate]} />);

    expect(srcs()).toContain(`/api/v1/operator/profiles/${PROFILE}/artifacts/salary-jpeg-1`);
    expect(images()).toHaveLength(6);
  });

  it('never puts a PDF certificate in an <img>', () => {
    // A PDF in an `<img src>` is a guaranteed broken image. The declared type is what decides the
    // tile shape, and the endpoint pins the served type from the same column.
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[...ALL_FIVE, PDF_CERTIFICATE]} />);

    expect(images()).toHaveLength(5);
    expect(srcs().join(' ')).not.toContain('salary-pdf-1');
    expect(screen.getByText('شهادة المرتب')).toBeInTheDocument();
  });

  it('opens a PDF certificate in a modal, with the bytes in an iframe and nothing in the address bar', async () => {
    const user = userEvent.setup();
    const before = window.location.pathname;
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[...ALL_FIVE, PDF_CERTIFICATE]} />);

    expect(document.querySelector('iframe')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'شهادة المرتب — أرفقها العميل' }));

    const frame = await waitFor(() => {
      const found = document.querySelector('iframe');
      expect(found).not.toBeNull();
      return found as HTMLIFrameElement;
    });
    expect(frame.getAttribute('src')).toBe(`/api/v1/operator/profiles/${PROFILE}/artifacts/salary-pdf-1`);
    // Ticket 01: the artifact id lives in the fetch, never in a route -- an iframe keeps it out of
    // browser history, which is exactly what opening a new tab would not do.
    expect(window.location.pathname).toBe(before);
    expect(window.location.pathname).not.toContain('salary-pdf-1');
  });

  it('closes the document modal, dropping the iframe and its fetch with it', async () => {
    const user = userEvent.setup();
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={[...ALL_FIVE, PDF_CERTIFICATE]} />);

    await user.click(screen.getByRole('button', { name: 'شهادة المرتب — أرفقها العميل' }));
    await waitFor(() => expect(document.querySelector('iframe')).not.toBeNull());

    const closeButton = document.querySelector('.ant-modal-close') as HTMLElement | null;
    expect(closeButton).not.toBeNull();
    await user.click(closeButton as HTMLElement);

    // The iframe is unmounted rather than hidden: a mounted one holding a customer's pay document
    // behind a closed modal is exactly the sort of thing nobody remembers is still there.
    await waitFor(() => expect(document.querySelector('iframe')).toBeNull());
  });

  it('opens the enlarged view on click, carrying the same src and no route change', async () => {
    const user = userEvent.setup();
    render(<ArtifactContactSheet documentType="SDN_ID" profileId={PROFILE} salaryCertificateState="DECLINED" artifacts={ALL_FIVE} />);

    await user.click(screen.getByAltText('وجه وثيقة الهوية — البطاقة القومية'));

    // The preview renders a second <img> at the same src -- the tile's own is still on the page.
    const expected = `/api/v1/operator/profiles/${PROFILE}/artifacts/doc-front-1`;
    await waitFor(() => {
      expect(images().filter((img) => img.getAttribute('src') === expected).length).toBeGreaterThan(1);
    });
    expect(window.location.pathname).not.toContain('doc-front-1');
  });
});
