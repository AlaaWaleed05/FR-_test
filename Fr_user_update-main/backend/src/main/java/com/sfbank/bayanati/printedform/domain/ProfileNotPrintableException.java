package com.sfbank.bayanati.printedform.domain;

/**
 * The profile exists and the operator is entitled to print, but the profile is not in a status that
 * may be printed.
 *
 * <p>Only {@code submitted} and {@code approved} (ticket 05 decision 8). This used to need a second
 * justification, because manual completion also set {@code status = 'submitted'} and the
 * branch-visit case had to be covered by the same two statuses. AD-022 deleted manual completion at
 * S9-01, so {@code submission.service.SubmissionService} is now the ONLY path into {@code
 * submitted} and the two statuses cover the whole printable population by construction. Simpler,
 * and the guard itself does not change.
 *
 * <p>Distinct from {@code UnknownProfileException} on purpose: a 404 here would tell an operator
 * that a profile they can see on their own screen does not exist.
 */
public class ProfileNotPrintableException extends RuntimeException {

  public ProfileNotPrintableException(String message) {
    super(message);
  }
}
