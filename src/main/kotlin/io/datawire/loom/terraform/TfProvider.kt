package io.datawire.loom.terraform

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonIgnore

data class TfProvider(
        @get:JsonIgnore val name: String,
        @get:JsonAnyGetter val params: Map<String, String>)