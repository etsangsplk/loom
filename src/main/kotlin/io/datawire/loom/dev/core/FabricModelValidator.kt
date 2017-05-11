package io.datawire.loom.dev.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import io.datawire.loom.dev.aws.AwsCloud
import io.datawire.loom.dev.model.validation.*


private val MODEL_NAME_REGEX = Regex("[a-z][a-z0-9_]{0,31}")

class FabricModelValidator(private val aws: AwsCloud) : Validator() {

  override fun validate(root: JsonNode) {
    val issues = mutableListOf<ValidationIssue>()

    root.matches(field("/name"), MODEL_NAME_REGEX)?.let { issues += it }

    root.validate(
        field("/sshPublicKey"),
        nullable = true,
        type     = JsonNodeType.STRING,
        check    = { true },
        failed   = issue("Value is Too Low", "Master node count is below allowed value: 0")
    )?.let { issues += it }

    root.validate(
        field("/region"),
        nullable = false,
        type     = JsonNodeType.STRING,
        check    = { aws.isUsableRegion(textValue()) },
        failed   = issue("Invalid Cloud Region", "Cloud provider region is not valid or usable")
    )?.let { issues += it }

    root.validate(
        field("/masterType"),
        nullable = false,
        type     = JsonNodeType.STRING,
        check    = { aws.isUsableMasterType(textValue()) },
        failed   = issue("Invalid Node Type", "Master node type is not valid or usable")
    )?.let { issues += it }

    root.validate(
        field("/masterCount"),
        nullable = true,
        type     = JsonNodeType.NUMBER,
        check    = { intValue() >= 0 },
        failed   = issue("Value is Too Low", "Master node count is below allowed value: 0")
    )?.let { issues += it }

    root.validate(
        field("/domain"),
        nullable = false,
        type     = JsonNodeType.STRING,
        check    = { aws.isOwnedRoute53Domain(textValue()) },
        failed   = issue("Unknown Domain", "Domain was not found in Amazon Route 53")
    )?.let { issues += it }

    root.validate(
        field("/sshPublicKey"),
        nullable = true,
        type     = JsonNodeType.STRING,
        check    = { true },
        failed   = issue("Value is Too Low", "Master node count is below allowed value: 0")
    )?.let { issues += it }

    root.validate(
        field("/nodeGroups"),
        nullable = false,
        type     = JsonNodeType.OBJECT,
        check    = { true },
        failed   = issue("Value is Too Low", "Master node count is below allowed value: 0")
    )?.let { issues += it }

    if (issues.isNotEmpty()) {
      throw ValidationException(issues)
    }
  }
}