package com.sfbank.bayanati.operator.domain;

import java.util.Optional;
import java.util.UUID;

/** The one way application code reads a single profile for the operator view. */
public interface ProfileViewRepository {

  Optional<ProfileDetail> find(UUID profileId);
}
