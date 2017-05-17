package io.datawire.loom.terraform

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.datawire.loom.core.Json
import io.datawire.loom.terraform.jackson.OutputDeserializer
import io.datawire.loom.terraform.jackson.ProviderDeserializer
import java.nio.file.Path


@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Template(
    @JsonProperty("terraform")
    val terraform: TerraformBlock? = null,

    @JsonProperty("provider")
    @JsonDeserialize(contentUsing = ProviderDeserializer::class)
    val providers: Map<String, Provider> = emptyMap(),

    @JsonProperty("module")
    @JsonDeserialize(contentUsing = ModuleDeserializer::class)
    val modules: Map<String, Module> = emptyMap(),

    @JsonProperty("output")
    @JsonDeserialize(contentUsing = OutputDeserializer::class)
    val outputs: Map<String, OutputReference> = emptyMap()
) {

  fun render(output: Path) = Json().writeUsingView<TemplateView>(this, output)

  fun render() = Json().writeUsingView<TemplateView>(this)
}

fun terraformTemplate(
    terraform: TerraformBlock? = null,
    providers: List<Provider> = emptyList(),
    modules: List<Module> = emptyList(),
    outputs: List<OutputReference> = emptyList()
) = Template(
    terraform,
    providers.associateBy { it.name },
    modules.associateBy { it.name },
    outputs.associateBy { it.name }
)