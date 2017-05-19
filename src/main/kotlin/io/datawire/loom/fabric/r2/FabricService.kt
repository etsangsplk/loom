package io.datawire.loom.fabric.r2

import io.datawire.loom.core.LoomException
import io.datawire.loom.core.aws.AwsCloud
import io.datawire.loom.fabric.*
import io.datawire.loom.kops.*
import io.datawire.loom.terraform.*
import java.util.concurrent.Executors


class FabricService(
    private val amazon  : AwsCloud,
    private val models  : FabricModelDao,
    private val fabrics : FabricSpecDao
) {

  private val tasks = Executors.newFixedThreadPool(4)

  fun getModel(name: String): FabricModel? = models.fetchModel(name)

  fun createModel(model: FabricModel): FabricModel {
    return synchronized(this) { models.createModel(model) }
  }

  fun createFabric(config: FabricConfig): FabricSpec {
    return synchronized(this) {
      val normalizedConfig = config.normalize()

      val spec = fabrics.createSpec(
          assembleFabricSpec(
              model  = getModel(normalizedConfig.model) ?: throw LoomException(404),
              config = config
          ))

      // All infrastructure management is driven through Terraform so we need to generate a lot of configuration files.

      // generate Terraform templates for the actual Fabric
      val workspace = getWorkspace(spec.name)
      workspace.writeTerraformTemplate("backend",  createS3BackendTemplate(spec.name))
      workspace.writeTerraformTemplate("override", createAwsProviderTemplate(spec.region))
      workspace.writeTerraformTemplate("main",     generateTemplate(spec))

      // generate Kops configuration.
      val cluster = spec.toClusterConfig(amazon.stateStorageBucketName)
      val masters = spec.createMasterInstanceGroupConfigs()
      val workers = spec.createWorkerInstanceGroupConfigs()

      // generate Terraform module from the Kops configuration.
      Kops2.newKops(workspace, amazon.stateStorageBucketName).apply {
        createCluster(cluster)
        (masters + workers).forEach { createInstanceGroup(it) }
        createSshPublicKeySecret(spec.clusterDomain, spec.sshPublicKey)
        updateCluster(spec.clusterDomain)
      }

      // Add additional Terraform output to the Kubernetes cluster module so that we can get the clusters routing
      // table identifier and VPC CIDR.
      val extraClusterOutputs = terraformTemplate(
          outputs = listOf(
              OutputReference(
                  name  = "route_table_id",
                  value = "\${aws_route_table.${spec.clusterDomain.replace('.', '-')}.id}"
              ),

              OutputReference(
                  name  = "vpc_cidr_block",
                  value = "\${aws_vpc.${spec.clusterDomain.replace('.', '-')}.cidr_block}"),

              OutputReference(
                  name  = "node_security_groups_count",
                  value = "\${aws_security_group.nodes-${spec.clusterDomain.replace('.', '-')}.count}"
              )
          )
      )

      workspace.writeTerraformTemplate(
          path     = workspace.terraform.resolve("kubernetes_${spec.clusterDomain}"),
          name     = "extra_outputs",
          template = extraClusterOutputs
      )

      // schedule a task to plan and apply the work via Terraform.

      spec
    }
  }

  /**
   * Retrieves a [FabricWorkspace] for the given fabric name. If the workspace does not exist on the filesystem then it
   * will be created.
   *
   * @param fabricName the name of the fabric.
   */
  private fun getWorkspace(fabricName: String) = getOrCreateWorkspace(fabricName)

  private fun createS3BackendTemplate(fabricName: String) = terraformTemplate(terraformBlock(createS3Backend(
      region  = amazon.stateStorageBucketRegion,
      bucket  = amazon.stateStorageBucketName,
      key     = "terraform/$fabricName.tfstate",
      encrypt = true
  )))

  private fun createAwsProviderTemplate(region: String) = terraformTemplate(
      providers = listOf(createAwsProvider(region))
  )

  private fun generateTemplate(spec: FabricSpec): Template {
    // TODO: Multi-cluster support
    //
    // It seems plausible that at some point in the future a Fabric will need to be able to support multiple Kubernetes
    // clusters. To make that transition easier we will generate modules named after the cluster DNS name which must be
    // unique.

    val kubernetes = Module(
        name   = "kubernetes_${spec.clusterDomain.replace('.', '-')}",
        source = "./kubernetes_${spec.clusterDomain}"
    )

    val clusters = listOf(kubernetes)

    val nodeSecurityGroups = if (clusters.size == 1) {
      clusters[0].outputList("node_security_group_ids")
    } else {
      TerraformList("\${concat(${clusters.joinToString(separator = ", ") { it.outputRef("node_security_group_ids") } })")
    }

    val externalServicesNetwork = Module(
        name      = "external_services_network",
        source    = spec.resourcesNetwork.module,
        variables = mapOf(
            "name"                        to TerraformString("fabric-${spec.name}"),
            "cidr_block"                  to TerraformString(spec.resourcesNetwork.cidr),
            "node_security_groups"        to nodeSecurityGroups,
            "node_security_groups_count"  to clusters[0].outputString("node_security_groups_count")
        )
    )

    val clusterPeeredWithResourcesNetwork = Module(
        name      = "external_services_network-${kubernetes.name}",
        source    = "github.com/datawire/loom//terraform/network-peer?ref=plombardi%2fexperimental",
        variables = mapOf(
            "external_services_vpc"                       to externalServicesNetwork.outputString("vpc_id"),
            "external_services_vpc_cidr"                  to externalServicesNetwork.outputString("cidr_block"),
            "external_services_vpc_external_route_table"  to externalServicesNetwork.outputString("external_route_table_id"),
            "external_services_vpc_internal_route_tables" to externalServicesNetwork.outputList("internal_route_table_ids"),
            "external_services_vpc_internal_route_tables_count" to externalServicesNetwork.outputString("internal_route_table_count"),

            "kubernetes_vpc"             to kubernetes.outputString("vpc_id"),
            "kubernetes_vpc_cidr"        to kubernetes.outputString("vpc_cidr_block"),
            "kubernetes_vpc_route_table" to kubernetes.outputString("route_table_id")
        )
    )

    val modules = listOf(
        kubernetes,
        externalServicesNetwork,
        clusterPeeredWithResourcesNetwork
    ) + generateCustomModules()

    return terraformTemplate(
        modules = modules,
        outputs = listOf(
            OutputReference("${kubernetes.name}_vpc_id", kubernetes.outputString("vpc_id")),
            OutputReference("external_services_vpc_id", externalServicesNetwork.outputString("vpc_id"))
        )
    )
  }

  private fun generateCustomModules(): List<Module> = emptyList()
}