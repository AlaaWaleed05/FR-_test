import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'
import { message } from 'antd'

// Vitest, unlike Jest, does not auto-register RTL's cleanup -- without this, DOM from one test
// (and its still-mounted effects) leaks into the next.
//
// BL-156: cleanup() is NOT enough on its own. antd's `message` API is a MODULE-LEVEL SINGLETON
// that mounts its toasts into a container of its own, appended straight to <body> and therefore
// outside the React tree RTL rendered -- so cleanup() never sees it. A toast raised by one test
// is still in the document when the next test queries it. Measured on an untouched `main` at
// d66ee2a: run 1 failed 1 of 185 (ProfileDetailPage's «تعذر اعتماد الملف (409)» toast still
// mounted when the print assertion ran), run 2 passed 185 of 185, and the file passed 33 of 33
// in isolation. `npm run test:coverage` is a required gate, so this could fail a session that
// changed nothing.
//
// **BL-156 PRESCRIBED `message.destroy()` ALONE, AND THAT DOES NOT WORK.** Proved here before
// relying on it: destroy() does not remove the node, it starts antd's fade-leave ANIMATION, and
// jsdom implements no CSS transitions and fires no transitionend -- so the notice settles on
// `ant-message-fade-leave-active` and stays in the document for ever. Measured directly: still
// present synchronously, after a macrotask, and after 100ms, with one `.ant-message` container
// still a child of <body>.
//
// So both steps are needed, and each does a different half: destroy() clears the singleton's
// own internal queue (without it antd still believes those notices are live), and removing the
// stranded notice nodes is what actually empties the DOM.
//
// ONLY THE NOTICES, NEVER THE `.ant-message` CONTAINER. Removing the container instead was
// tried first and broke four ProfileDetailPage print tests: antd caches its holder, so the
// NEXT message.success() rendered into a container no longer attached to the document and
// nothing appeared. The container is antd's; the stranded children are the leak.
//
// `.ant-message-notice` is the notice ROOT in antd 6 and `.ant-message-notice-wrapper` is its
// DESCENDANT -- the reverse of antd 5. Removing the root takes the wrapper with it, so the
// root alone is the whole selector.
//
// WHY THIS DOES NOT FIGHT REACT. Detaching a node React still owns would normally risk a
// later `NotFoundError: Failed to execute 'removeChild'`. It cannot here, because antd's
// message sets NO `motionDeadline` (unlike badge/drawer/tooltip/upload/wave), so with no
// jsdom transitionend the leave motion never completes, React never unmounts the notice fiber
// and never tries to remove the node. That is a property of antd, not of this file: a future
// antd minor that gives message a motionDeadline would surface as exactly that NotFoundError
// from any test that opens a toast.
afterEach(() => {
  cleanup()
  message.destroy()
  document.querySelectorAll('.ant-message-notice').forEach((notice) => notice.remove())
})

// jsdom has no ResizeObserver; antd's Table/Select internals (rc-resize-observer) need one.
if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
}

// jsdom has no matchMedia implementation; antd's Grid/breakpoint observer needs one.
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList;
}
