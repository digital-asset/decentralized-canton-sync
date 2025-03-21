// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates.
// Proprietary code. All rights reserved.

package com.daml.ledger.javaapi.data;

import com.daml.ledger.api.v2.admin.UserManagementServiceOuterClass;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.NonNull;

public final class User {

  private final String id;
  private final Optional<String> primaryParty;

  public User(@NonNull String id) {
    this.id = id;
    this.primaryParty = Optional.empty();
  }

  public User(@NonNull String id, @NonNull String primaryParty) {
    this.id = id;
    this.primaryParty = Optional.of(primaryParty);
  }

  public UserManagementServiceOuterClass.User toProto() {
    if (primaryParty == null || primaryParty.isEmpty()) {
      return UserManagementServiceOuterClass.User.newBuilder()
        .setId(id)
        .build();
    } else {
      return UserManagementServiceOuterClass.User.newBuilder()
        .setId(id)
        .setPrimaryParty(primaryParty.get())
        .build();
    }
  }

  public static User fromProto(UserManagementServiceOuterClass.User proto) {
    String id = proto.getId();
    String primaryParty = proto.getPrimaryParty();
    if (primaryParty == null || primaryParty.isEmpty()) {
      return new User(id);
    } else {
      return new User(id, primaryParty);
    }
  }

  @NonNull
  public String getId() {
    return id;
  }

  public Optional<String> getPrimaryParty() {
    return primaryParty;
  }

  @Override
  public String toString() {
    return "User{"
        + "id='"
        + id
        + '\''
        + primaryParty.map(p -> ", primaryParty='" + p + '\'').orElse("")
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    User user = (User) o;
    return Objects.equals(id, user.id) && Objects.equals(primaryParty, user.primaryParty);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, primaryParty);
  }

  public abstract static class Right {

    abstract UserManagementServiceOuterClass.Right toProto();

    public static Right fromProto(UserManagementServiceOuterClass.Right proto) {
      UserManagementServiceOuterClass.Right.KindCase kindCase = proto.getKindCase();
      Right right;
      switch (kindCase) {
        case CAN_ACT_AS:
          right = new CanActAs(proto.getCanActAs().getParty());
          break;
        case CAN_READ_AS:
          right = new CanReadAs(proto.getCanReadAs().getParty());
          break;
        case PARTICIPANT_ADMIN:
          // since this is a singleton so far we simply ignore the actual object
          right = ParticipantAdmin.INSTANCE;
          break;
        case IDENTITY_PROVIDER_ADMIN:
          // since this is a singleton so far we simply ignore the actual object
          right = IdentityProviderAdmin.INSTANCE;
          break;
        case CAN_READ_AS_ANY_PARTY:
          // since this is a singleton so far we simply ignore the actual object
          right = CanReadAsAnyParty.INSTANCE;
          break;
        default:
          throw new IllegalArgumentException("Unrecognized user right case: " + kindCase.name());
      }
      return right;
    }

    public static final class IdentityProviderAdmin extends Right {
      // empty private constructor, singleton object
      private IdentityProviderAdmin() {}
      // not built lazily on purpose, close to no overhead here
      public static final IdentityProviderAdmin INSTANCE = new IdentityProviderAdmin();

      @Override
      UserManagementServiceOuterClass.Right toProto() {
        return UserManagementServiceOuterClass.Right.newBuilder()
            .setIdentityProviderAdmin(
                UserManagementServiceOuterClass.Right.IdentityProviderAdmin.getDefaultInstance())
            .build();
      }
    }

    public static final class ParticipantAdmin extends Right {
      // empty private constructor, singleton object
      private ParticipantAdmin() {}
      // not built lazily on purpose, close to no overhead here
      public static final ParticipantAdmin INSTANCE = new ParticipantAdmin();

      @Override
      UserManagementServiceOuterClass.Right toProto() {
        return UserManagementServiceOuterClass.Right.newBuilder()
            .setParticipantAdmin(
                UserManagementServiceOuterClass.Right.ParticipantAdmin.getDefaultInstance())
            .build();
      }
    }

    public static final class CanActAs extends Right {
      public final String party;

      public CanActAs(String party) {
        this.party = party;
      }

      @Override
      UserManagementServiceOuterClass.Right toProto() {
        return UserManagementServiceOuterClass.Right.newBuilder()
            .setCanActAs(
                UserManagementServiceOuterClass.Right.CanActAs.newBuilder().setParty(this.party))
            .build();
      }
    }

    public static final class CanReadAs extends Right {
      public final String party;

      public CanReadAs(String party) {
        this.party = party;
      }

      @Override
      UserManagementServiceOuterClass.Right toProto() {
        return UserManagementServiceOuterClass.Right.newBuilder()
            .setCanReadAs(
                UserManagementServiceOuterClass.Right.CanReadAs.newBuilder().setParty(this.party))
            .build();
      }
    }

    public static final class CanReadAsAnyParty extends Right {
      // empty private constructor, singleton object
      private CanReadAsAnyParty() {}
      // not built lazily on purpose, close to no overhead here
      public static final CanReadAsAnyParty INSTANCE = new CanReadAsAnyParty();

      @Override
      UserManagementServiceOuterClass.Right toProto() {
        return UserManagementServiceOuterClass.Right.newBuilder()
            .setCanReadAsAnyParty(
                UserManagementServiceOuterClass.Right.CanReadAsAnyParty.getDefaultInstance())
            .build();
      }
    }
  }
}
