package io.github.zachelks.proxkeep.core.api.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiSchemaTest {

    private val schema = ApiSchema.parse(javaClass.getResource("/apidata-excerpt.js")!!.readText())
    private val update = assertNotNull(schema.method(CONFIG, "POST"))
    private val net = assertNotNull(update.parameter("net0"))
    private val agent = assertNotNull(update.parameter("agent"))

    @Test
    fun readsEveryPathFromTheViewersDataFile() {
        // Paths without methods of their own, such as /nodes here, are left out.
        assertEquals(setOf("/nodes/{node}/qemu", CONFIG, "/version"), schema.paths)
        assertEquals(setOf("GET", "POST"), schema.endpoint(CONFIG)!!.methods.keys)
        assertEquals("update_vm_async", update.name)
        assertEquals("The user needs 'VM.Config.*' permissions for each changed setting.", update.permissions)
    }

    @Test
    fun theViewersCodeAfterTheSchemaIsLeftOut() {
        // The server publishes the schema and the viewer in one script, apidoc.js.
        val script = """const apiSchema = [{"path":"/version","info":{"GET":{"method":"GET","description":"a ] in [ text"}}}];

Ext.define('PVE.APIViewer', { items: [ { xtype: 'treepanel' } ] });"""
        assertEquals(setOf("/version"), ApiSchema.parse(script).paths)
    }

    @Test
    fun readsPlainJsonToo() {
        val plain = ApiSchema.parse("""[{"path":"/version","info":{"GET":{"method":"GET"}}}]""")
        assertNotNull(plain.method("/version", "GET"))
    }

    @Test
    fun rejectsFilesWithoutASchema() {
        assertFailsWith<IllegalArgumentException> { ApiSchema.parse("<html>Not found</html>") }
        assertFailsWith<IllegalArgumentException> { ApiSchema.parse("const apiSchema = [{\"path\":\"/x\"") }
    }

    @Test
    fun readsParameterDetails() {
        val cores = assertNotNull(update.parameter("cores"))
        assertEquals(ParamType.Integer, cores.type)
        assertEquals(1.0, cores.minimum)
        assertEquals("1", cores.default)
        assertTrue(cores.optional)

        val onboot = assertNotNull(update.parameter("onboot"))
        assertEquals(ParamType.Boolean, onboot.type)
        assertEquals("0", onboot.default)

        val vmid = assertNotNull(update.parameter("vmid"))
        assertFalse(vmid.optional)
        assertEquals("pve-vmid", vmid.format)
        assertEquals(listOf("other", "wxp"), update.parameter("ostype")!!.enum.take(2))
        assertEquals(8192, update.parameter("description")!!.maxLength)
    }

    @Test
    fun numberedKeysMatchTheirTemplate() {
        assertEquals("net[n]", net.name)
        assertTrue(net.isIndexed)
        assertEquals("net", net.baseName)
        assertTrue(net.matches("net12"))
        assertFalse(net.matches("net"))
        assertFalse(net.matches("netx"))
        assertNull(update.parameter("scsi0"))
    }

    @Test
    fun readsTheKeysOfPropertyStrings() {
        assertTrue(net.isPropertyString)
        val model = assertNotNull(net.property("model"))
        assertTrue(model.defaultKey)
        assertEquals("virtio", model.enum[1])
        val virtio = assertNotNull(net.property("virtio"))
        assertEquals("model", virtio.keyAlias)
        assertEquals("macaddr", virtio.alias)
        assertEquals(4094.0, net.property("tag")!!.maximum)
        assertTrue(agent.property("enabled")!!.defaultKey)
    }

    @Test
    fun propertyStringsRoundTrip() {
        val text = "virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1,tag=20"
        val parsed = PropertyString.parse(text, net)
        assertEquals("virtio", parsed["model"])
        assertEquals("BC:24:11:2A:3B:4C", parsed["macaddr"])
        assertEquals("vmbr0", parsed["bridge"])
        assertEquals(true, parsed.flag("firewall"))
        assertEquals(text, parsed.encode(net))

        val changed = parsed.with("tag", null).with("bridge", "vmbr1").with("rate", "12.5")
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr1,firewall=1,rate=12.5", changed.encode(net))
    }

    @Test
    fun aNewNetworkDeviceNamesItsModelBare() {
        val fresh = PropertyString.Empty.with("bridge", "vmbr0").with("model", "virtio")
        assertEquals("virtio,bridge=vmbr0", fresh.encode(net))
        assertEquals("e1000", PropertyString.parse("e1000,bridge=vmbr0", net)["model"])
    }

    @Test
    fun theDefaultKeyCanBeWrittenBareOrNamed() {
        assertEquals("1", PropertyString.parse("1", agent)["enabled"])
        val named = PropertyString.parse("enabled=1,fstrim_cloned_disks=1", agent)
        assertEquals("1", named["enabled"])
        assertEquals("1,fstrim_cloned_disks=1", named.encode(agent))
    }

    @Test
    fun checksValuesAgainstTheSchema() {
        val cores = update.parameter("cores")!!
        assertNull(cores.check(""))
        assertNull(cores.check("4"))
        assertEquals(ValueProblem(ProblemKind.NotAWholeNumber), cores.check("2.5"))
        assertEquals(ValueProblem(ProblemKind.BelowMinimum, limit = 1.0), cores.check("0"))

        val vmid = update.parameter("vmid")!!
        assertEquals(ValueProblem(ProblemKind.Required), vmid.check(""))
        assertEquals(ProblemKind.AboveMaximum, vmid.check("1000000000")?.kind)
        assertEquals(ProblemKind.NotAChoice, update.parameter("ostype")!!.check("dos")?.kind)
        assertEquals(ProblemKind.NotAChoice, update.parameter("onboot")!!.check("maybe")?.kind)
        assertEquals(ValueProblem(ProblemKind.TooLong, limit = 8192.0), update.parameter("description")!!.check("x".repeat(8193)))
    }

    @Test
    fun checksEachKeyOfAPropertyString() {
        assertNull(net.check("virtio,bridge=vmbr0,tag=100"))
        assertEquals(ValueProblem(ProblemKind.AboveMaximum, key = "tag", limit = 4094.0), net.check("virtio,bridge=vmbr0,tag=5000"))
        assertEquals(ValueProblem(ProblemKind.NotAChoice, key = "model"), net.check("ne2k,bridge=vmbr0"))
        assertEquals(ValueProblem(ProblemKind.Required, key = "model"), net.check("bridge=vmbr0"))
    }

    @Test
    fun patternsMatchTheWholeValue() {
        val named = ParamSchema("hostname", ParamType.String, pattern = "[a-z][a-z0-9-]*")
        assertNull(named.check("web-1"))
        assertEquals(ProblemKind.WrongFormat, named.check("web 1")?.kind)
        // Perl syntax Java cannot read is left to the server.
        assertNull(ParamSchema("x", ParamType.String, pattern = "(?<!").check("anything"))
    }

    private companion object {
        const val CONFIG = "/nodes/{node}/qemu/{vmid}/config"
    }
}
