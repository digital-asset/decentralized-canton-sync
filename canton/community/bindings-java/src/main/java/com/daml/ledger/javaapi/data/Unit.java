// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.javaapi.data;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.google.protobuf.Empty;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class Unit extends Value {

  private static Unit instance = new Unit();

  private Unit() {}

  @NonNull
  public static Unit getInstance() {
    return Unit.instance;
  }

  @Override
  public ValueOuterClass.Value toProto() {
    Empty empty = Empty.newBuilder().build();
    ValueOuterClass.Value value = ValueOuterClass.Value.newBuilder().setUnit(empty).build();
    return value;
  }

  @Override
  public String toString() {
    return "Unit{}";
  }
}
