import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PrintFormModal from './PrintFormModal';

const OPEN_PROPS = { submitting: false, hasAttachments: true };

describe('PrintFormModal', () => {
  it('asks the attachments question and no longer offers a choice of form', () => {
    // Ticket 05 decision 9 put the certificate question and the variant choice in the SAME
    // interaction. AD-022 (S9-01) removed the variant, so one question remains -- and the radios
    // are asserted ABSENT rather than simply dropped from the test, because a one-form product
    // that still offers a menu of two is the visible symptom of a half-applied ruling.
    render(<PrintFormModal {...OPEN_PROPS} onCancel={vi.fn()} onSubmit={vi.fn()} />);

    expect(screen.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).toBeInTheDocument();
    expect(screen.queryByRole('radio')).toBeNull();
    expect(screen.queryByText(/أسماء المدخلين/)).toBeNull();
  });

  it('defaults to attachments OFF', async () => {
    // "Asked every time, defaulting to NO." An operator who once opted in would otherwise go on
    // printing customers' pay documents indefinitely without ever deciding to again.
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<PrintFormModal {...OPEN_PROPS} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('button', { name: 'طباعة' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ includeAttachments: false }));
  });

  it('sends attachments when the operator opts in', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    render(<PrintFormModal {...OPEN_PROPS} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ }));
    await user.click(screen.getByRole('button', { name: 'طباعة' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ includeAttachments: true }));
  });

  it('does not offer the attachments option when the profile carries none', () => {
    // Not shown DISABLED -- not shown at all. An option that produces nothing invites an operator
    // to wonder whether the documents exist and they simply cannot reach them.
    //
    // The ordinary case for this used to be a manually completed profile, which had no artifacts
    // of any kind; AD-022 removed that journey, so the remaining case is a submitted profile whose
    // uploads never landed. The behaviour is unchanged and still worth pinning.
    render(<PrintFormModal {...OPEN_PROPS} hasAttachments={false} onCancel={vi.fn()} onSubmit={vi.fn()} />);

    expect(screen.queryByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).toBeNull();
    // The dialog itself still renders -- the question is gone, not the print action.
    expect(screen.getByRole('button', { name: 'طباعة' })).toBeInTheDocument();
  });

  it('re-asks from the defaults on the next print rather than remembering the last answer', async () => {
    // The requirement, not a nicety: "A remembered preference is the failure mode this shape
    // exists to prevent." The mechanism is a fresh MOUNT per print -- the page renders this only
    // while its dialog is open -- so the test drives the answer away from its default, unmounts,
    // mounts again, and submits without touching anything.
    //
    // This drove BOTH answers away from their defaults until AD-022 left only one. The guard is
    // unweakened: the reset it proves is the component's, and one sticky answer is as bad as two.
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    const first = render(<PrintFormModal {...OPEN_PROPS} onCancel={vi.fn()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ }));
    expect(screen.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).toBeChecked();

    first.unmount();
    render(<PrintFormModal {...OPEN_PROPS} onCancel={vi.fn()} onSubmit={onSubmit} />);

    expect(screen.getByRole('checkbox', { name: /طباعة المرفقات مع الاستمارة/ })).not.toBeChecked();

    await user.click(screen.getByRole('button', { name: 'طباعة' }));

    await waitFor(() =>
      expect(onSubmit).toHaveBeenLastCalledWith({ includeAttachments: false }),
    );
  });

  it('says that every print is recorded, and no longer claims the form is internal', () => {
    // The internal-use notice is RETIRED from every surface at S9-06 — AD-022 (k). This test
    // used to assert it here, on the reasoning that "the screen and the paper agree". They do
    // still agree: neither says it. Ticket 05 decision 7 rules the form internal as a matter of
    // process and never required the words anywhere, and the product owner has confirmed the
    // requirement was an earlier session's invention rather than the bank's.
    //
    // The negative assertion is the point of keeping this test: without it, re-adding the notice
    // to the one surface it was last removed from would pass silently.
    render(<PrintFormModal {...OPEN_PROPS} onCancel={vi.fn()} onSubmit={vi.fn()} />);

    expect(screen.getByText(/تُقيَّد العملية في سجل التدقيق/)).toBeInTheDocument();
    expect(screen.queryByText(/للاستخدام الداخلي/)).not.toBeInTheDocument();
    expect(screen.queryByText(/لا تُسلَّم للعميل/)).not.toBeInTheDocument();
  });
});
