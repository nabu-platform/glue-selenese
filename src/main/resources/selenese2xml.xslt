<stylesheet version="1.0" xmlns="http://www.w3.org/1999/XSL/Transform" xmlns:xhtml="http://www.w3.org/1999/xhtml">

	<strip-space elements="*" />

	<template match="/">
		<apply-templates select="xhtml:html"/>
	</template>

	<template match="/xhtml:html">
		<element name="testCase" namespace="http://nabu.be/glue/selenese">
			<attribute name="name"><value-of select="xhtml:head/xhtml:title" /></attribute>
			<attribute name="target"><value-of select="xhtml:head/xhtml:link[@rel='selenium.base']/@href" /></attribute>
			<apply-templates select="//xhtml:table/xhtml:tbody"/>
		</element>
	</template>
	
	<template match="//xhtml:table/xhtml:tbody">
		<apply-templates select="xhtml:tr"/>
	</template>
	
	<template match="xhtml:tr">
		<element name="step" namespace="http://nabu.be/glue/selenese">
			<attribute name="action"><value-of select="xhtml:td[1]"/></attribute>
			<attribute name="target"><value-of select="xhtml:td[2]"/></attribute>
			<value-of select="xhtml:td[3]"/>
		</element>
	</template>
	
	<template match="text()"/>
</stylesheet>