package com.sfbank.bayanati.accountcheck.web;

/**
 * The two fields journey Stage 1a collects.
 *
 * @param branch the branch code selected from the server-supplied {@code branch} reference list
 * @param accountNumber the account number the customer typed
 */
public record AccountCheckRequest(String accountNumber) {}
