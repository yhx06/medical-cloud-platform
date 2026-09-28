' Generates an editable PowerDesigner 16.5 EAM with two course-lab diagrams.
' Run: cscript.exe //nologo generate-medical-cloud-iaas.vbs [output.eam]
' No physical or virtual servers are started by this script.
Option Explicit
On Error Resume Next

Const K_ARCH_AREA = 1712643291
Const K_HARDWARE_SERVER = 1800760343
Const K_SOFTWARE_SERVER = -233502108
Const K_NETWORK = -1750244528
Const K_NETWORK_NODE = -180370349
Const K_WORKSTATION = 376140120
Const K_INFRA_LINK = 1999962906

Dim fso, shell, pd, model, scenario, cluster, scriptDir, templatePath
Dim outputPath, tempPath, tempName, i, j, row, col, groupIndex
Dim layers(5), roles(3), groupNodes(6), servers(69), coreNode
Dim internetObj, tomcatObj, wildflyObj, mysqlObj, linkCount, configText
Dim groupX, groupY, serverX, serverY, groupLabel, number

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
templatePath = "D:\Sybase\PowerDesigner 16\ShellNew\PDModel16.eam"
outputPath = fso.BuildPath(scriptDir, "medical-cloud-iaas-generated.eam")
If WScript.Arguments.Count > 0 Then outputPath = fso.GetAbsolutePathName(WScript.Arguments(0))
If Not fso.FileExists(templatePath) Then Fail "PowerDesigner 16.5 EAM template not found: " & templatePath
If fso.FileExists(outputPath) Then Fail "Output already exists; choose a new filename: " & outputPath

' PowerDesigner 16.5 OLE OpenModel rejects the Chinese characters in this
' workspace path. Build from the vendor template at an ASCII-only temp path,
' close the model, then copy the saved native EAM to the requested output.
tempName = "medical-cloud-iaas-" & fso.GetBaseName(fso.GetTempName) & ".eam"
tempPath = fso.BuildPath(shell.ExpandEnvironmentStrings("%TEMP%"), tempName)
fso.CopyFile templatePath, tempPath, False
Check "copy EAM template"
Set pd = CreateObject("PowerDesigner.Application")
Check "create PowerDesigner application"
Set model = pd.OpenModel(tempPath, 0)
Check "open temporary EAM"
If model Is Nothing Then Fail "PowerDesigner returned no EAM model"
model.Name = "Medical Cloud IaaS Architecture"
model.Code = "MedicalCloudIaaSArchitecture"
Check "name EAM model"

Set scenario = model.TechnologyInfrastructureDiagrams.CreateNew
Check "create scenario diagram"
scenario.Name = "Medical Cloud Application Scenario"
scenario.Code = "MedicalCloudApplicationScenario"
Check "name scenario diagram"

' Six main course layers, ordered from foundation to users.
Set layers(0) = AddArea(scenario, "Foundation Layer - secure room", "High-performance servers, high-speed network, mass storage and dynamic resource allocation.", 32000, 3500, 60000, 5800)
Set layers(1) = AddArea(scenario, "Virtualization Layer", "Compute, storage and network virtualization; resource pooling.", 32000, 10500, 60000, 4800)
Set layers(2) = AddArea(scenario, "Cloud Platform Layer - IaaS / PaaS / SaaS", "IaaS resources; WildFly 10 PaaS; Tomcat 9 SaaS.", 32000, 18500, 60000, 8500)
Set layers(3) = AddArea(scenario, "Medical Cloud Service Environment", "Service components plus portal and HTTP/JSON service interfaces.", 32000, 28500, 60000, 7000)
Set layers(4) = AddArea(scenario, "Internet", "Internet access to the medical cloud portal.", 32000, 36500, 60000, 4800)
Set layers(5) = AddArea(scenario, "Users - four service roles", "Patient; doctor and hospital staff; regulator (planned); API developer.", 32000, 44500, 60000, 8500)

Call AddArea(scenario, "High-performance servers", "Foundation computing resources.", 9000, 3500, 12000, 2600)
Call AddArea(scenario, "High-speed communication network", "IPv4/IPv6 communication.", 24000, 3500, 15000, 2600)
Call AddArea(scenario, "Mass storage", "Shared storage design resource.", 39000, 3500, 10000, 2600)
Call AddArea(scenario, "Dynamic allocation", "On-demand allocation design capability.", 52500, 3500, 12000, 2600)
Call AddArea(scenario, "Compute / Storage / Network virtualization", "Logical resource pool; not a claim of deployment.", 32000, 10500, 45000, 2500)
Call AddArea(scenario, "IaaS - compute / network / storage", "70 logical servers in the second diagram.", 12000, 18500, 17500, 4200)
Call AddArea(scenario, "PaaS - business API and data", "WildFly 10; JPA/JNDI; MySQL data service.", 32000, 18500, 17500, 4200)
Call AddArea(scenario, "SaaS - medical cloud portal", "Tomcat 9 /portal with role-specific pages.", 52000, 18500, 17500, 4200)
Call AddArea(scenario, "Appointment / Consultation / Medical Record / Simulated Order", "Implemented classroom service components; simulated payment is not real payment.", 25000, 28500, 35000, 3500)
Call AddArea(scenario, "Portal + HTTP/JSON Service Interface", "Tomcat /portal calls WildFly /paas/api.", 51500, 28500, 17000, 3500)
Call AddArea(scenario, "Design model, not actual 70-server deployment", "All infrastructure capacity in this diagram is a course modeling requirement.", 32000, 51000, 59000, 2500)

Set tomcatObj = AddObject(scenario, K_SOFTWARE_SERVER, "Tomcat 9 /portal (SaaS)", "Implemented classroom portal.", 51000, 19500, 10000, 2500)
Set wildflyObj = AddObject(scenario, K_SOFTWARE_SERVER, "WildFly 10 /paas/api (PaaS)", "HTTP/JSON API; EJB/JPA business service.", 32000, 19500, 11500, 2500)
Set mysqlObj = AddObject(scenario, K_SOFTWARE_SERVER, "MySQL data service", "JPA/JNDI persistence. Not deployed on 70 nodes by this model.", 32000, 16500, 10000, 2100)
Set internetObj = AddObject(scenario, K_NETWORK, "Internet access network", "Access between users and the medical cloud.", 32000, 36500, 9000, 2300)
Set roles(0) = AddObject(scenario, K_WORKSTATION, "Patient / service customer", "Online registration, appointment and record/order viewing.", 8500, 44500, 12000, 2400)
Set roles(1) = AddObject(scenario, K_WORKSTATION, "Doctor + hospital staff / provider", "Consultation and hospital operations.", 24500, 44500, 14000, 2400)
Set roles(2) = AddObject(scenario, K_WORKSTATION, "Regulator / management (planned)", "Role shown for the experiment; regulatory statistics are not implemented.", 42000, 44500, 14500, 2400)
Set roles(3) = AddObject(scenario, K_WORKSTATION, "Service API developer", "Service interface development role.", 56500, 44500, 11000, 2400)
For i = 0 To 3
    Call AddLink(scenario, roles(i), internetObj, "User access " & CStr(i + 1))
Next
Call AddLink(scenario, internetObj, tomcatObj, "Internet to portal")
Call AddLink(scenario, tomcatObj, wildflyObj, "Portal HTTP JSON to PaaS")
Call AddLink(scenario, wildflyObj, mysqlObj, "JPA JNDI data access")

Set cluster = model.TechnologyInfrastructureDiagrams.CreateNew
Check "create cluster diagram"
cluster.Name = "IaaS Server Cluster - 70 Logical Servers"
cluster.Code = "IaaSServerCluster70"
Check "name cluster diagram"
Call AddArea(cluster, "Secure machine room (design)", "All 70 are model objects, not deployed instances.", 33000, 56500, 63000, 3000)
configText = "Intel i5 3.2 GHz; course expression 3.2*2^10*2^10 operations (not benchmarked); 4 GB memory; 100 GB storage; IPv4 and IPv6; Windows OS; antivirus; firewall; intrusion prevention; secure machine room. Course-only logical specification, not measured capacity."
Call AddArea(cluster, "Common template: i5 3.2 GHz | 4 GB | 100 GB | Windows | IPv4/IPv6 | antivirus/firewall/intrusion prevention", configText, 33000, 61500, 64000, 3500)
Call AddArea(cluster, "Design model only - 70 logical/virtual servers, NOT actually running", "Seven groups are layout groups, not real sites or availability zones.", 33000, 4000, 64000, 2800)
Set coreNode = AddObject(cluster, K_NETWORK_NODE, "Core dual-stack network node", "IPv4/IPv6 switch fabric for all seven groups.", 33000, 31000, 7200, 3400)

For groupIndex = 0 To 6
    row = groupIndex Mod 4
    col = groupIndex \ 4
    If col = 0 Then groupX = 14500 Else groupX = 51500
    groupY = 48500 - row * 11000
    groupLabel = "Layout group " & CStr(groupIndex + 1) & " - 10 servers"
    Call AddArea(cluster, groupLabel, "Display grouping only; 10 logical servers connected through one network node.", groupX, groupY, 29000, 10000)
    Set groupNodes(groupIndex) = AddObject(cluster, K_NETWORK_NODE, "Group " & CStr(groupIndex + 1) & " network node", "Dual-stack local switch; uplink to core node.", groupX, groupY - 3650, 4500, 1700)
    Call AddLink(cluster, groupNodes(groupIndex), coreNode, "Group " & CStr(groupIndex + 1) & " uplink")
    For j = 0 To 9
        number = groupIndex * 10 + j + 1
        serverX = groupX - 10400 + (j Mod 5) * 5200
        If j < 5 Then serverY = groupY + 1700 Else serverY = groupY - 1250
        Set servers(number - 1) = AddObject(cluster, K_HARDWARE_SERVER, "IaaS-" & Right("0" & CStr(number), 2), configText, serverX, serverY, 3600, 1900)
        Call AddLink(cluster, servers(number - 1), groupNodes(groupIndex), "Server " & CStr(number) & " to group node")
    Next
Next

model.Save
Check "save generated EAM"
model.Close False
Check "close generated EAM"
fso.CopyFile tempPath, outputPath, False
Check "copy generated EAM to project"
fso.DeleteFile tempPath, True
Check "remove only the temporary EAM"
WScript.Echo "CREATED: " & outputPath
WScript.Echo "2 diagrams; 70 logical servers; 8 network nodes; " & CStr(linkCount) & " infrastructure links."

Sub Fail(message)
    WScript.Echo "ERROR: " & message
    WScript.Quit 1
End Sub

Sub Check(label)
    If Err.Number <> 0 Then Fail label & ": " & CStr(Err.Number) & " " & Err.Description
    Err.Clear
End Sub

Function Place(diagram, item, x, y, width, height)
    Dim symbol, position
    Set symbol = diagram.AttachObject(item)
    Check "attach " & item.Name
    Set position = symbol.Position
    position.X = x
    position.Y = y
    symbol.Position = position
    symbol.Width = width
    symbol.Height = height
    Check "place " & item.Name
    Set Place = symbol
End Function

Function AddObject(diagram, kind, name, comment, x, y, width, height)
    Dim item, symbol
    Set item = model.CreateObject(kind)
    Check "create " & name
    item.Name = name
    item.Code = Replace(Replace(Replace(name, " ", "_"), "/", "_"), "-", "_")
    item.Comment = comment
    Check "label " & name
    Set symbol = Place(diagram, item, x, y, width, height)
    Set AddObject = item
End Function

Function AddArea(diagram, name, comment, x, y, width, height)
    Set AddArea = AddObject(diagram, K_ARCH_AREA, name, comment, x, y, width, height)
End Function

Sub AddLink(diagram, item1, item2, name)
    Dim link, symbol
    Set link = model.CreateObject(K_INFRA_LINK)
    Check "create link " & name
    link.Name = name
    link.Code = "LINK_" & CStr(linkCount + 1)
    Set link.Object1 = item1
    Set link.Object2 = item2
    Check "connect " & name
    Set symbol = diagram.AttachObject(link)
    Check "draw link " & name
    linkCount = linkCount + 1
End Sub
