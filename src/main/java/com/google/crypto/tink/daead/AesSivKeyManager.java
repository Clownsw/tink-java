// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.daead;

import static com.google.crypto.tink.internal.TinkBugException.exceptionIsBug;

import com.google.crypto.tink.AccessesPartialKey;
import com.google.crypto.tink.DeterministicAead;
import com.google.crypto.tink.KeyTemplate;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.Registry;
import com.google.crypto.tink.SecretKeyAccess;
import com.google.crypto.tink.config.internal.TinkFipsUtil;
import com.google.crypto.tink.daead.internal.AesSivProtoSerialization;
import com.google.crypto.tink.internal.KeyTypeManager;
import com.google.crypto.tink.internal.MutableKeyDerivationRegistry;
import com.google.crypto.tink.internal.MutableParametersRegistry;
import com.google.crypto.tink.internal.MutablePrimitiveRegistry;
import com.google.crypto.tink.internal.PrimitiveConstructor;
import com.google.crypto.tink.internal.PrimitiveFactory;
import com.google.crypto.tink.internal.Util;
import com.google.crypto.tink.proto.AesSivKey;
import com.google.crypto.tink.proto.AesSivKeyFormat;
import com.google.crypto.tink.proto.KeyData.KeyMaterialType;
import com.google.crypto.tink.subtle.AesSiv;
import com.google.crypto.tink.subtle.Random;
import com.google.crypto.tink.subtle.Validators;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This key manager generates new {@code AesSivKey} keys and produces new instances of {@code
 * AesSiv}.
 */
public final class AesSivKeyManager extends KeyTypeManager<AesSivKey> {
  private static final PrimitiveConstructor<
          com.google.crypto.tink.daead.AesSivKey, DeterministicAead>
      AES_SIV_PRIMITIVE_CONSTRUCTOR =
          PrimitiveConstructor.create(
              AesSiv::create,
              com.google.crypto.tink.daead.AesSivKey.class,
              DeterministicAead.class);

  AesSivKeyManager() {
    super(
        AesSivKey.class,
        new PrimitiveFactory<DeterministicAead, AesSivKey>(DeterministicAead.class) {
          @Override
          public DeterministicAead getPrimitive(AesSivKey key) throws GeneralSecurityException {
            return new AesSiv(key.getKeyValue().toByteArray());
          }
        });
  }

  private static final int KEY_SIZE_IN_BYTES = 64;

  @Override
  public TinkFipsUtil.AlgorithmFipsCompatibility fipsStatus() {
    return TinkFipsUtil.AlgorithmFipsCompatibility.ALGORITHM_NOT_FIPS;
  }

  @Override
  public String getKeyType() {
    return "type.googleapis.com/google.crypto.tink.AesSivKey";
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public KeyMaterialType keyMaterialType() {
    return KeyMaterialType.SYMMETRIC;
  }

  @Override
  public void validateKey(AesSivKey key) throws GeneralSecurityException {
    Validators.validateVersion(key.getVersion(), getVersion());
    if (key.getKeyValue().size() != KEY_SIZE_IN_BYTES) {
      throw new InvalidKeyException(
          "invalid key size: "
              + key.getKeyValue().size()
              + ". Valid keys must have "
              + KEY_SIZE_IN_BYTES
              + " bytes.");
    }
  }

  @Override
  public AesSivKey parseKey(ByteString byteString) throws InvalidProtocolBufferException {
    return AesSivKey.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
  }

  @Override
  public KeyFactory<AesSivKeyFormat, AesSivKey> keyFactory() {
    return new KeyFactory<AesSivKeyFormat, AesSivKey>(AesSivKeyFormat.class) {
      @Override
      public void validateKeyFormat(AesSivKeyFormat format) throws GeneralSecurityException {
        if (format.getKeySize() != KEY_SIZE_IN_BYTES) {
          throw new InvalidAlgorithmParameterException(
              "invalid key size: "
                  + format.getKeySize()
                  + ". Valid keys must have "
                  + KEY_SIZE_IN_BYTES
                  + " bytes.");
        }
      }

      @Override
      public AesSivKeyFormat parseKeyFormat(ByteString byteString)
          throws InvalidProtocolBufferException {
        return AesSivKeyFormat.parseFrom(byteString, ExtensionRegistryLite.getEmptyRegistry());
      }

      @Override
      public AesSivKey createKey(AesSivKeyFormat format) throws GeneralSecurityException {
        return AesSivKey.newBuilder()
            .setKeyValue(ByteString.copyFrom(Random.randBytes(format.getKeySize())))
            .setVersion(getVersion())
            .build();
      }
    };
  }

  @SuppressWarnings("InlineLambdaConstant") // We need a correct Object#equals in registration.
  private static final MutableKeyDerivationRegistry.InsecureKeyCreator<AesSivParameters>
      KEY_DERIVER = AesSivKeyManager::createAesSivKeyFromRandomness;

  @AccessesPartialKey
  static com.google.crypto.tink.daead.AesSivKey createAesSivKeyFromRandomness(
      AesSivParameters parameters,
      InputStream stream,
      @Nullable Integer idRequirement,
      SecretKeyAccess access)
      throws GeneralSecurityException {
    return com.google.crypto.tink.daead.AesSivKey.builder()
        .setParameters(parameters)
        .setIdRequirement(idRequirement)
        .setKeyBytes(Util.readIntoSecretBytes(stream, parameters.getKeySizeBytes(), access))
        .build();
  }

  private static Map<String, Parameters> namedParameters() throws GeneralSecurityException {
    Map<String, Parameters> result = new HashMap<>();
    result.put("AES256_SIV", PredefinedDeterministicAeadParameters.AES256_SIV);
    result.put(
        "AES256_SIV_RAW",
        AesSivParameters.builder()
            .setKeySizeBytes(64)
            .setVariant(AesSivParameters.Variant.NO_PREFIX)
            .build());
    return Collections.unmodifiableMap(result);
  }

  public static void register(boolean newKeyAllowed) throws GeneralSecurityException {
    Registry.registerKeyManager(new AesSivKeyManager(), newKeyAllowed);
    AesSivProtoSerialization.register();
    MutablePrimitiveRegistry.globalInstance()
        .registerPrimitiveConstructor(AES_SIV_PRIMITIVE_CONSTRUCTOR);
    MutableParametersRegistry.globalInstance().putAll(namedParameters());
    MutableKeyDerivationRegistry.globalInstance().add(KEY_DERIVER, AesSivParameters.class);
  }

  /**
   * @return a {@code KeyTemplate} that generates new instances of AES-SIV-CMAC keys.
   */
  public static final KeyTemplate aes256SivTemplate() {
    return exceptionIsBug(
        () ->
            KeyTemplate.createFrom(
                AesSivParameters.builder()
                    .setKeySizeBytes(KEY_SIZE_IN_BYTES)
                    .setVariant(AesSivParameters.Variant.TINK)
                    .build()));
  }

  /**
   * @return A {@code KeyTemplate} that generates new instances of AES-SIV-CMAC keys. Keys generated
   *     from this template create ciphertexts compatible with other libraries.
   */
  public static final KeyTemplate rawAes256SivTemplate() {
    return exceptionIsBug(
        () ->
            KeyTemplate.createFrom(
                AesSivParameters.builder()
                    .setKeySizeBytes(KEY_SIZE_IN_BYTES)
                    .setVariant(AesSivParameters.Variant.NO_PREFIX)
                    .build()));
  }
}
