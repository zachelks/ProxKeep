// The schema at the start of /pve-docs/api-viewer/apidoc.js, cut down, in the shape Proxmox VE 8 and 9 publish it.
const apiSchema = [
   {
      "children" : [
         {
            "children" : [
               {
                  "children" : [
                     {
                        "info" : {
                           "GET" : {
                              "description" : "Get the virtual machine configuration with pending configuration changes applied.",
                              "method" : "GET",
                              "name" : "vm_config",
                              "parameters" : {
                                 "additionalProperties" : 0,
                                 "properties" : {
                                    "node" : { "description" : "The cluster node name.", "format" : "pve-node", "type" : "string" },
                                    "vmid" : { "description" : "The (unique) ID of the VM.", "format" : "pve-vmid", "maximum" : 999999999, "minimum" : 100, "type" : "integer" }
                                 }
                              },
                              "permissions" : { "check" : [ "perm", "/vms/{vmid}", [ "VM.Audit" ] ] },
                              "returns" : { "type" : "object" }
                           },
                           "POST" : {
                              "description" : "Set virtual machine options (asynchronous API).",
                              "method" : "POST",
                              "name" : "update_vm_async",
                              "parameters" : {
                                 "additionalProperties" : 0,
                                 "properties" : {
                                    "agent" : {
                                       "description" : "Enable/disable communication with the QEMU Guest Agent and its properties.",
                                       "format" : {
                                          "enabled" : { "default" : 0, "default_key" : 1, "description" : "Enable/disable communication with a QEMU Guest Agent (QGA) running in the VM.", "type" : "boolean" },
                                          "fstrim_cloned_disks" : { "default" : 0, "description" : "Run fstrim after moving a disk or migrating the VM.", "optional" : 1, "type" : "boolean" },
                                          "type" : { "default" : "virtio", "description" : "Select the agent type", "enum" : [ "virtio", "isa" ], "optional" : 1, "type" : "string" }
                                       },
                                       "optional" : 1,
                                       "type" : "string",
                                       "typetext" : "[enabled=]<1|0> [,fstrim_cloned_disks=<1|0>] [,type=<virtio|isa>]"
                                    },
                                    "cores" : { "default" : 1, "description" : "The number of cores per socket.", "minimum" : 1, "optional" : 1, "type" : "integer" },
                                    "delete" : { "description" : "A list of settings you want to delete.", "format" : "pve-configid-list", "optional" : 1, "type" : "string" },
                                    "description" : { "description" : "Description for the VM. Shown in the web-interface VM's summary.", "maxLength" : 8192, "optional" : 1, "type" : "string" },
                                    "name" : { "description" : "Set a name for the VM. Only used on the configuration web interface.", "format" : "dns-name", "optional" : 1, "type" : "string" },
                                    "net[n]" : {
                                       "description" : "Specify network devices.",
                                       "format" : {
                                          "bridge" : { "description" : "Bridge to attach the network device to.", "format_description" : "bridge", "optional" : 1, "type" : "string" },
                                          "e1000" : { "alias" : "macaddr", "keyAlias" : "model" },
                                          "firewall" : { "description" : "Whether this interface should be protected by the firewall.", "optional" : 1, "type" : "boolean" },
                                          "macaddr" : { "description" : "MAC address.", "format" : "mac-addr", "optional" : 1, "type" : "string" },
                                          "model" : { "default_key" : 1, "description" : "Network Card Model.", "enum" : [ "e1000", "virtio", "rtl8139", "vmxnet3" ], "type" : "string" },
                                          "rate" : { "description" : "Rate limit in mbps (megabytes per second) as floating point number.", "minimum" : 0, "optional" : 1, "type" : "number" },
                                          "tag" : { "description" : "VLAN tag to apply to packets on this interface.", "maximum" : 4094, "minimum" : 1, "optional" : 1, "type" : "integer" },
                                          "virtio" : { "alias" : "macaddr", "keyAlias" : "model" }
                                       },
                                       "optional" : 1,
                                       "type" : "string",
                                       "typetext" : "[model=]<enum> [,bridge=<bridge>] [,firewall=<1|0>] [,macaddr=<XX:XX:XX:XX:XX:XX>] [,rate=<number>] [,tag=<integer>]"
                                    },
                                    "node" : { "description" : "The cluster node name.", "format" : "pve-node", "type" : "string" },
                                    "onboot" : { "default" : 0, "description" : "Specifies whether a VM will be started during system bootup.", "optional" : 1, "type" : "boolean" },
                                    "ostype" : { "description" : "Specify guest operating system.", "enum" : [ "other", "wxp", "w2k", "w2k3", "w2k8", "wvista", "win7", "win8", "win10", "win11", "l24", "l26", "solaris" ], "optional" : 1, "type" : "string" },
                                    "tags" : { "description" : "Tags of the VM. This is only meta information.", "format" : "pve-tag-list", "optional" : 1, "type" : "string" },
                                    "vmid" : { "description" : "The (unique) ID of the VM.", "format" : "pve-vmid", "maximum" : 999999999, "minimum" : 100, "type" : "integer" }
                                 }
                              },
                              "permissions" : { "description" : "The user needs 'VM.Config.*' permissions for each changed setting." },
                              "protected" : 1,
                              "returns" : { "optional" : 1, "type" : "string" }
                           }
                        },
                        "leaf" : 1,
                        "path" : "/nodes/{node}/qemu/{vmid}/config",
                        "text" : "config"
                     }
                  ],
                  "info" : {
                     "GET" : { "description" : "Virtual machine index (per node).", "method" : "GET", "name" : "vmlist", "parameters" : { "properties" : { "node" : { "type" : "string" } } } }
                  },
                  "leaf" : 0,
                  "path" : "/nodes/{node}/qemu",
                  "text" : "qemu"
               }
            ],
            "leaf" : 0,
            "path" : "/nodes/{node}",
            "text" : "{node}"
         }
      ],
      "leaf" : 0,
      "path" : "/nodes",
      "text" : "nodes"
   },
   {
      "info" : {
         "GET" : { "description" : "API version details, including some parts of the global datacenter config.", "method" : "GET", "name" : "version", "parameters" : { "additionalProperties" : 0 }, "permissions" : { "user" : "all" } }
      },
      "leaf" : 1,
      "path" : "/version",
      "text" : "version"
   }
]
;
let method2cmd = { "GET /version" : "pvesh get /version" };
