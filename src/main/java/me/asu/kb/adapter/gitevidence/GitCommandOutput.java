package me.asu.kb.adapter.gitevidence;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded output from one fixed-profile Git subprocess. */
record GitCommandOutput(int exitCode, byte[] standardOutput) {
  GitCommandOutput {
    standardOutput = standardOutput.clone();
  }

  @Override
  public byte[] standardOutput() {
    return standardOutput.clone();
  }

  String strictUtf8() throws GitProbeException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(standardOutput))
          .toString();
    } catch (CharacterCodingException error) {
      throw new GitProbeException(
          GitEvidenceDiagnosticCode.ENCODING_ERROR, "Git stdout is not valid UTF-8");
    }
  }
}
