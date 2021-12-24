@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

package org.jesperancinha.plugins.spring

import com.sun.org.apache.xerces.internal.dom.DeferredDocumentImpl
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

private val Node.innerBean: Node?
    get() = childNodes.toList.firstOrNull() { it != null && it.nodeName == "bean" }

private val Node.map: Node
    get() = childNodes.toList.first { it.nodeName == "map" }

private val Node.list: Node
    get() = childNodes.toList.first { it.nodeName == "list" }

private val Node.hasTypeList: Boolean
    get() = childNodes.toList.firstOrNull { it != null && it.nodeName == "list" } != null

private val Node.hasTypeMap: Boolean
    get() = childNodes.toList.firstOrNull { it != null && it.nodeName == "map" } != null

private val Node.hasValue: Boolean
    get() = attributes?.getNamedItem("value") != null

private val Node.hasInnerValue: Boolean
    get() = childNodes.toList.firstOrNull { it != null && it.nodeName == "value" } != null

private val Node.innerValueType: String?
    get() = childNodes.toList.firstOrNull { it != null && it.nodeName == "value" }?.attributes?.getNamedItem("type")?.nodeValue

private val Node.innerValue: String?
    get() = childNodes.toList.firstOrNull { it != null && it.nodeName == "value" }?.childNodes?.item(0)?.nodeValue

private val NodeList.toPropertiesList: List<Node>
    get() = toList.filter { it != null && it.nodeName == "property" }

private val NodeList.toList: List<Node>
    get() = (0..length).map { item(it) }

private val beanById = mutableMapOf<String, Node>()
private val beanByType = mutableMapOf<String, Node>()

fun main(args: Array<String>) {
    val sourceFolder = args[0]
    val targetConfigFile = args[1]
    val packageString = args[2]
    println("Hello, World")
    println("Configured target configuration file is $targetConfigFile")
    createClass(targetConfigFile, packageString, sourceFolder)

}

fun createClass(targetConfigFile: String, packageString: String, sourceFolder: String) {
    val targetConfigurationFile = File(targetConfigFile)
    val sb = mutableListOf<String>()
    sb.add("package $packageString\n\n")
    sb.add("import org.springframework.context.annotation.Configuration\n")
    sb.add("import org.springframework.context.annotation.Bean\n")
    println("Configured source folder is $sourceFolder")
    println("Found these file:")
    File(sourceFolder).listFiles { beanFile ->
        beanFile.extension == "xml"
    }?.forEach {
        processFile(it, sb)
    }
    createImports(sb)
    createBeans(sb, targetConfigurationFile)
    sb.add("\n}")
    targetConfigurationFile.writeText(sb.joinToString(""))
}

private fun createBeans(sb: MutableList<String>, targetConfigurationFile: File) {
    sb.add("\n\n@Configuration\nclass ${targetConfigurationFile.nameWithoutExtension} {\n\n")
    beanById.forEach { (id, bean) ->
        createBean(sb, id, bean)
    }
}


/**
 * Bean creation
 */
private fun createBean(sb: MutableList<String>, id: String, bean: Node) {
    sb.add(" @Bean\n fun $id(\n")
    val functionIndex = AtomicInteger(sb.size - 1)
    sb.add("\n\n) = ")
    createBeanInstance(sb, bean, functionIndex)
    sb.add("\n\n")
}

private fun createBeanInstance(sb: MutableList<String>, bean: Node, functionIndex: AtomicInteger) {
    sb.add("${bean.attributes.getNamedItem("class").nodeValue.split(".").last()}(")
    sb.add(")")
    val properties = bean.childNodes.toPropertiesList
    if (properties.isNotEmpty()) {
        sb.add(".apply {\n")
        val parameters = mutableSetOf<String>()
        properties.forEach { property ->
            sb.add("set${property.attributes.getNamedItem("name").nodeValue.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}(\n")
            if (property.hasTypeList) createList(property.list, sb, functionIndex, parameters)
            if (property.hasValue) sb.add("\"${property.attributes.getNamedItem("value").nodeValue}\"")
            if (property.hasInnerValue) {
                if (property.innerValueType == "java.lang.Boolean") sb.add("${property.innerValue}")
                else sb.add("\"${property.innerValue}\"")
            }
            if (property.hasTypeMap) createMap(property.map, sb, functionIndex, parameters)

            sb.add(")\n")
        }
        val prev = sb[functionIndex.get()]
        val joinToString = parameters.filter {
            !prev.contains(it)
        }.joinToString(",\n")
        sb[functionIndex.get()] = prev + (if (!prev.endsWith("(\n") && joinToString.isNotEmpty()) ",\n" else "") + joinToString
        sb.add("}\n")
    }
}

fun createMap(property: Node, sb: MutableList<String>, functionIndex: AtomicInteger, parameters: MutableSet<String>) {
    sb.add("mapOf(\n")
    property.childNodes.toList.forEach { entry ->
        entry?.apply {
            val attributes = entry.attributes
            attributes?.apply {
                var node = entry.innerBean
                val nodeAttributes = node?.attributes
                val itemId = nodeAttributes?.getNamedItem("id")?.nodeValue
                val itemType: String? = nodeAttributes?.getNamedItem("class")?.nodeValue ?: attributes?.getNamedItem("value-ref")?.nodeValue
                val beanRef = attributes?.getNamedItem("value-ref")?.nodeValue

                node?.apply {
                    if (itemId == null && itemType != null) {
                        if (!node.hasChildNodes()) {
                            val importElement = "import $itemType\n"
                            if (!sb.contains(importElement)) {
                                sb.add(2, importElement)
                                functionIndex.addAndGet(1)
                            }
                            val inputParamName = "${itemType.split(".").last().replaceFirstChar { it.lowercase(Locale.getDefault()) }}"
                            sb.add("\"${entry.attributes.getNamedItem("key").nodeValue}\" to $inputParamName")
                            sb.add(",\n")
                            parameters.add("$inputParamName: ${inputParamName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}")
                        } else {
                            sb.add("\"${entry.attributes.getNamedItem("key").nodeValue}\" to ")
                            createBeanInstance(sb, node, functionIndex)
                            sb.add(",\n")
                        }
                    }
                }


                if (itemId == null && beanRef != null) {
                    val nodeRef = beanById[beanRef]
                    val itemType = nodeRef?.attributes?.getNamedItem("class")?.nodeValue
                    itemType?.apply {
                        val importElement = "import $itemType\n"
                        if (!sb.contains(importElement)) {
                            sb.add(2, importElement)
                            functionIndex.addAndGet(1)
                        }
                    }
                    sb.add("\"${entry.attributes.getNamedItem("key").nodeValue}\" to $beanRef,")
                    parameters.add("$beanRef: ${beanRef.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}")
                }
            }
        }
    }
    sb.add(")\n")
}

private fun createList(property: Node, sb: MutableList<String>, functionIndex: AtomicInteger, parameters: MutableSet<String>) {
    sb.add("listOf(\n")
    property.childNodes.toList.forEach { node ->
        node?.apply {
            val attributes = node.attributes
            attributes?.apply {
                val itemId = attributes.getNamedItem("id")?.nodeValue
                val itemType = attributes.getNamedItem("class")?.nodeValue
                val beanRef = attributes.getNamedItem("bean")?.nodeValue
                if (itemId == null && itemType != null) {
                    if (!node.hasChildNodes()) {
                        val importElement = "import $itemType\n"
                        if (!sb.contains(importElement)) {
                            sb.add(2, importElement)
                            functionIndex.addAndGet(1)
                        }
                        val inputParamName = "${itemType.split(".").last().replaceFirstChar { it.lowercase(Locale.getDefault()) }}"
                        sb.add(inputParamName)
                        sb.add(",\n")
                        parameters.add("$inputParamName: ${inputParamName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}")
                    } else {
                        createBeanInstance(sb, node, functionIndex)
                        sb.add(",\n")
                    }
                }


                if (itemId == null && beanRef != null) {
                    val nodeRef = beanById[beanRef]
                    val itemType = nodeRef?.attributes?.getNamedItem("class")?.nodeValue
                    itemType?.apply {
                        val importElement = "import $itemType\n"
                        if (!sb.contains(importElement)) {
                            sb.add(2, importElement)
                            functionIndex.addAndGet(1)
                        }
                    }
                    sb.add("$beanRef,\n")
                    parameters.add("$beanRef: ${beanRef.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}")
                }
            }
        }
    }
    sb.add(")\n")
}

private fun createImports(sb: MutableList<String>) {
    beanById.forEach { (id, bean) ->
        val import = "import ${bean.attributes.getNamedItem("class").nodeValue}\n"
        if (!sb.contains(import)) sb.add(import)
    }
}

fun processFile(it: File, sb: MutableList<String>): String {
    println("1.- Processing file ${it.name} ...")
    val beanLines = it.readLines(Charset.defaultCharset())
    if (beanLines[0].startsWith("<bean")) {
        processFileDefinite(it, sb)
        return "SUCCESS"
    } else if (!beanLines[1].startsWith("<bean")) {
        processFileDefinite(it, sb)
        return "SUCCESS"
    }
    println("Fail! $it is not a spring bean file!")
    return "FAIL"
}

fun readXml(xmlFile: File): Document {
    val dbFactory = DocumentBuilderFactory.newInstance()
    val dBuilder = dbFactory.newDocumentBuilder()
    val xmlInput = InputSource(StringReader(xmlFile.readText()))
    val doc = dBuilder.parse(xmlInput)

    return doc
}

private fun parseNodeList(nodeList: List<Node>) {
    nodeList.forEach { node: Node? ->
        node?.apply {
            if (node.hasAttributes()) {
                val attributes = node.attributes
                if (node.nodeName == "bean") {
                    val itemId = attributes.getNamedItem("id").nodeValue
                    val itemType = attributes.getNamedItem("class").nodeValue
                    itemId?.apply { beanById[itemId] = node }
                    itemType?.apply { beanByType[itemType] = node }
                    if (node.hasChildNodes()) {
                        parseNodeList(node.childNodes.toList)
                    }
                }
            }
        }
    }
}

fun processFileDefinite(beanFile: File, sb: MutableList<String>) {
    println("2.- File ${beanFile.name} is a Spring bean. Processing....")
    val readXml = readXml(xmlFile = beanFile)
    println("2.1.- Success read file ${beanFile.name} with result $readXml")

    val mainNode = (readXml.childNodes as DeferredDocumentImpl).childNodes.item(0)
    val nodeList = mainNode.childNodes.toList
    parseNodeList(nodeList)
}
