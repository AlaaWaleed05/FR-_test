import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { message } from 'antd';

/**
 * BL-156, made deterministic.
 *
 * The flake this guards against is intermittent by nature -- it depends on which test file ran
 * before which, and on whether antd's toast had faded before the next assertion queried the DOM.
 * A flake cannot be proved by re-running the suite, so the leak is reproduced here on purpose
 * instead: the first test raises a toast, and the second asserts the document it inherits is
 * clean.
 *
 * What makes the second test pass is BOTH halves of `setup.ts`'s afterEach -- `message.destroy()`
 * AND the removal of the stranded notice nodes. Neither alone is enough, and BL-156 prescribed
 * only the first; `setup.ts`'s own comment carries the measurement, and is the place to read it
 * rather than a second copy here that could drift.
 *
 * ORDER IS LOAD-BEARING. These two must stay in this file, in this order -- the second is
 * meaningless without the first having run.
 */
describe('test setup: antd message does not leak across tests (BL-156)', () => {
  const TOAST = 'رسالة من اختبار سابق';

  it('raises a toast that outlives its own React tree', async () => {
    render(<div>ignored</div>);
    void message.error(TOAST);
    // `waitFor`, not a bare `getBy`: antd mounts the toast asynchronously, into a container
    // OUTSIDE the tree render() just created -- which is precisely why cleanup() cannot
    // remove it.
    //
    // EXPLICIT TIMEOUT, because waitFor's default of 1000ms is not enough under `test:coverage`.
    // Measured across the four combinations rather than guessed: clean tree passes with and
    // without coverage, and so does this branch WITHOUT coverage -- it is only coverage plus a
    // longer suite that pushes antd's asynchronous mount past one second, which is the same v8
    // instrumentation slowdown `vite.config.ts` already documents for `testTimeout`.
    //
    // This does not weaken the guard. BL-156's substance is the SECOND test below, which asserts
    // that no toast survives into a test that did not raise one; this one only has to succeed in
    // raising a toast at all, and a timeout here fails the suite for a reason that has nothing to
    // do with leakage.
    await waitFor(() => expect(screen.getByText(TOAST)).toBeInTheDocument(), { timeout: 10_000 });
  });

  it('inherits a document with no toast in it', () => {
    expect(screen.queryByText(TOAST)).not.toBeInTheDocument();
  });
});
