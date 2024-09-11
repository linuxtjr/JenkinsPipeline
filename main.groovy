#!groovy
library 'fxg-reference-pipeline'
library 'reference-pipeline'
//def myFunctions = load './utilSappCheckout/issFunctions.groovy'
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
//////////////////////////////////////////////////   INFO: Parallel stages in Jenkins scripted pipelines            //////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   INFO: Function definitions are declared at bottom of page      //////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
// TODO: Add Timeouts, Finish Commenting/Notes, Add 3rd stage('Rerun Checkout on Error Sites')
// Note: sh() in Jenkins waits for command/s to complete, so there's no need for process.waitFor().
pipeline {
    agent any
        // Global Environment Variables: Pretty much anything that will ever need editing is in the ENVIRONMENT BLOCK below.
        environment {
            // Function getServerList() variables; They contain the server list path, dry run value and exclusion list.
            dryRunOnTestServers = 'No'                                  // 'No' to run on Production  or  'Yes' to run on Non-Production Sites (Testing)
            type = 'Basic'                                              // "Basic" or "Pretty" - Preferred HTML Document Appearance
            checkoutParameters = "Y Y N N Checkout Checkout Yes None"   // Test Performed:   DoGeneral, DoStatus, DoTestSend, DoEBSdownloads, DoPublisher, DoSubscriber, DoDatabseCheck, DoERROR_LOG
            testServers = /STPL9|NOKY9|LEHI9|LEHI8|WOOD9/               // Define test servers to pass to Function getServerList()
            emailAddress = 'FXGL3SortSupport@corp.ds.fedex.com SortX@corp.ds.fedex.com richard.barger@fedex.com thomas.gleason@fedex.com' //emailAddress = 'tj.richardson.osv@fedex.com'        
            emailSubject = 'ISS Peak Jenkins Output!'
            remoteUserName = 'oasis'    // SSH and Rsync(encrypted) privileged username
            emailAttachments = ['ERROR_LOG.log']     // Separate filenames with a comma.
            
            errorLogParams = "N N N NA NA NA No 15"                     // ERROR_LOG are pulled ONLY; No other test will be performed.
            
            // KEEP IN ENVIRONMENT BLOCK FOR NOW!!
            pathToServerList = 'Text_files/iss_machines_jenkins.txt'    // Host should be split by whitespace. list should contain fully qualified domain names and IP addresses.
            // Variables Needed for Remote Host Section
            delimiter = '---DELIMITER---'
            scriptLocation = 'CopyutilSISSCheckoutScriptOnServerTemp' // Path of script that will be copied
            remoteFilePath1 ='/tmp/ScriptErrors.log'                            // Log path on remote host
            remoteFilePath2 = '/tmp/CheckScript.log'                              // Log path on remote host
    }
    options { buildDiscarder(logRotator(numToKeepStr: '4')) }
    stages {
        //////////// STAGE ONE //////////////
        stage('Build List of Sites') {
            steps {
                script {
                    sshagent(credentials: ['DIY_SystemTeam_Master_SSH_Key']) {                                      
                        echoConsoleMessage("'Agent Assigned to Job' completed successfully")    // FUNCTION: print message in Jenkins Console. Used for debugging
                        
                        def serverListBatchSize = 16    // Controls # of SITES that are proces sed in parallel  
                        def counter = 0                 // keep track of number of sites successfully processed                
                        def bashParameters Map: [       // Structure containing parameters
                            DoGeneral:Y,                    // General Testing
                            DoStatus:Y,                     // Status Check 
                            DoTestSend:N,                   // Send Test to database
                            DoEBSdownloads:N,               // EBS
                            DoPublisher:Checkout,           // Check Publisher
                            DoSubscriber:Checkout,          // 
                            DoDatabaseCheck:Yes,            // 
                            DoERROR_LOG:None                // 
                            DoConsumeOPF:null               // 
                        ]
                        
                        CreateNewFiles('summary.html','error.log','summary.log')                                   // FUNCTION: Ensure log/html files are clear
                        def serverList = getServerList(testServers, pathToServerList, params.useCustomServerList) // FUNCTION: pulls list of HOSTS and stores in variable.
                        def serverListSplitIntoBatches = serverList.collate(serverListBatchSize)                  // Separate hosts into batches and prepare to loop through each
                        echoConsoleMessage("Stage 'Build List of Sites' completed successfully")                  // FUNCTION: print message in Jenkins Console. Used for debugging
        ///////////// STAGE TWO //////////////
        stage('Run Checkout on Sites') {
            serverListSplitIntoBatches.each { batchOfServers ->             // Iterate each mapping of servers in batches e.g. if batch size is 16 and there are 400 server.
                parallel batchOfServers.collectEntries { serverLine ->      // (PART-ONE) Iterate mapping of a single server batch, transforming serverLine into a unique key-value pair in a map.
                    def serverDetails = tokenizeServerLine(serverLine)      // FUNCTION: Splits string into tokens; delimiter is whitespace
                    def lowerDomain = [serverDetails.lowerDomain] // Stores tokenizeServerLine returned values in defined variables
                    def upperDomain = [serverDetails.upperDomain] // Stores tokenizeServerLine returned values in defined variables
                    def consumesOPF = [serverDetails.consumesOPF] // Stores tokenizeServerLine returned values in defined variables
                    
                    bashParameters['DoConsumeOPF'] = consumesOPF
                ["${lowerDomain}": {    // (PART-TWO) Mapping of a single site e.g. SERVER1 to everything after braces,creating a KEY|VALUE pair for each site and commands performed.
                    try {
                        def remoteCommandOutput = executeRemoteCommands(remoteUserName, // FUNCTION: Execute remote commands on Sites
                                                                        lowerDomain, 
                                                                        upperDomain, 
                                                                        scriptLocation,  
                                                                        updatedCheckoutParameters
                                                                       )
                        def networkStatus = checkNetworkStatus()    // **IMPORTANT** Function checkNetworkStatus() MUST come after remoteCommnandOuput(). This function grabs the exit code and runs a check!
                        
                        if (networkStatus != 'Up') {
                            sh(script: "echo '${lowerDomain} - ERROR: Something went wrong while using remote commands SCP/SSH. Check Network.' >> error.log")
                        }
                        else {
                            def logMessage = splitLogOutput(remoteCommandOutput, // FUNCTION: splits output, stores in files and variable
                                                            delimiter, 
                                                            lowerDomain
                                                           )
                            
                            def (errorMessage, summaryMessage) = [logMessage.error, logMessage.summary]   // Store returned saveOutput()
                            sh(script: "echo \"${summaryMessage}\" >> summary.log")
                            sh(script: "echo \"<div style='font-size: 16px;'><pre>${summaryMessage}</pre></div><br>\" >> summary.html")
                            
                            if (errorMessage.size() > 0) {
                                sh(script: "echo \"${errorMessage}\" >> error.log")
                            }
                        }
                        counter++   // Increment is the final step, indicating a host didn't fail within the TRY/CATCH block
                    }
                    catch (Exception e) {
                        handleError(lowerDomain, e.message) // FUNCTION: Writes exactly ONE row on ERROR table for site.
                                    }
                                }]
                            }
                        //parallel parallelSteps
                        }
                    }
                }
                // Assign files created in last stage using 'stash', which will allow them to easily be accessed in next step.
                stash includes: 'error.log', name: 'errorLog'
                stash includes: 'summary.log', name: 'summaryLog'
                stash includes: 'summary.html', name: 'summaryHtml'
                echoConsoleMessage("Stage 'Run Checkout on Sites' completed successfully")  // FUNCTION: print message in Jenkins Console. Used for debugging
            }
        }
    }
}
 post {
        always {
                script {
                        try {
                            //DOING A BIT OF LINE COUNTING, FORMATTING / COMBINING HTML (Outlook is picky about missing HTML tags) & SENDING OUT THE EMAIL
                            HTMLReport = 'HTMLReport.html'
                            unstash 'errorLog'
                            unstash 'summaryLog'
                            unstash 'summaryHtml'
                            removeEmptyLinesFromFile('error.log')
                            def errorLineCount = getLineCount('error.log')

                            if (type == 'Pretty') { // Pretty HTML structure with full styling
                                echo "Creating 'Pretty' HTML Document"
                                //def errorLineCount = getLineCount('error.log')
                                // Wrap lowerDomain, ERROR message, Available/Unavailabe, Dock loaded/Unloaded, Active/Inactive, Network Up/Down
                                //buildPrettyDocument(errorLog, summaryLog, errorLineCount, summaryLineCount, HTMLReport)
                            } 
                            else if (type == 'Basic') { // Basic HTML structure with just preformatted text
                                echo "Creating 'Basic' HTML Document"
                                buildBasicDocument('error.log', 
                                                   'summary.html', 
                                                   HTMLReport,
                                                   errorLineCount
                                                  )
                            } 
                            else {
                                echo "Unknown type: ${type}. Please specify either 'Basic' or 'Pretty'."
                            }
                            
                            sendEmailWithAttachments(emailAddress,  // Function: sendEmailWithAttachments(recipients, subject, body)
                                                     emailSubject, 
                                                     HTMLReport
                                                    )
                            cleanWs()
                        } 
                        catch (Exception e) {
                            handleError("Post Block", e.message)   // FUNCTION: Writes exactly ONE row on ERROR table for site.
                        }
                  }    
            }
      }
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   getServerList() - pulls the list of sites and returns result   ////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getServerList(testServers, filePath, customServerList) {
    return readFile(filePath)
        .readLines()
        .findAll { 
            //if (customServerList) {  // Check if customServerList is not empty
            if (customServerList && customServerList.size() > 0) {
                echo "TESTING REGEX HERE!!!: ${customServerList} PASS Conditional"
                it =~ /${customServerList}/       // If customServerList is provided, match only these servers
            } 
            else {
                if (params.dryRunOnTestServers == 'No') {
                    !(it =~ testServers)     // PRODUCTION: Exclude Test Servers
                } 
                else if (params.dryRunOnTestServers == 'Yes') {
                    it =~ testServers        // NON-PRODUCTION: Use this for Test servers
                } 
                else {
                    echo "No servers were selected."
                }
            }
        }
    //.take(4) // UNCOMMENT to limit the number of servers for testing, e.g., .take(30) to process only 30 servers.
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
//////////////////////////////////////////////////   clearFiles()  - Clear existing log files  /////////////////////////////////////////////////////////////////////////////////////////////////////////// 
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def createNewFiles(String... files) {
    files.each { file ->
        sh """
        true > ${file}
        """
    }
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   sortFileContents(filePath) - Provide this function     ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def writeToFileWithHtmlTags(data,filename) {
    // Append output to HTML File
    sh(script: "echo \"<hr><div><pre>${data}</pre></div><br>\" >> ${filename}")
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   sortFileContents(filePath) - Provide this function     ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def writeToFile(data,filename) {
    // Append output to File
    sh(script: "echo \"${data}\" >> ${filename}")
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   Splits output  //////////////////////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def tokenizeServerLine(serverLine) {
    def parts = serverLine.tokenize()
    if (parts.size() >= 6) {
        def lowerDomain = parts[0]
        def upperDomain = parts[1]
        def consumesOPF = parts[5]
        return [lowerDomain: lowerDomain, upperDomain: upperDomain, consumesOPF: consumesOPF]
    } 
    else {
        error "Server line has insufficient parts: ${serverLine}"
    }
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
////////////////////////////////////////////////////////////////////////    PRINT MESSAGE TO OCNSOLE   //////////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def echoConsoleMessage(message) {
    echo """
        ---------------------------------------------------------------------------------------------------------------------------------------
        ///////////////////////////////////////  ${message}  ////////////////////////////////////////
        ---------------------------------------------------------------------------------------------------------------------------------------
        """
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  
//////////////////////////////////////////////////   executreRemoteCommands                                  ///////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def executeRemoteCommands(remoteUserName, lowerDomain, upperDomain, scriptLocation, checkoutParams, delimiter, remoteFilePath1, remoteFilePath2) {
    def command = """
        scp -p -o StrictHostKeyChecking=no ./utilSappCheckout/${scriptLocation} ${remoteUserName}@${lowerDomain}${upperDomain}:/tmp/ && \\
        ##rsync -avh --update -e "ssh -o StrictHostKeyChecking=no" /tmp/${scriptLocation} ${remoteUserName}@${lowerDomain}${upperDomain}:/tmp/ && \\
        ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${remoteUserName}@${lowerDomain}${upperDomain} \\
        "chmod +x /tmp/${scriptLocation} && nohup /tmp/${scriptLocation} ${checkoutParams} >/dev/null 2>&1; \\
        echo ${delimiter}; cat ${remoteFilePath1}; \\
        echo ${delimiter}; cat ${remoteFilePath2}"
    """
    return sh(script: command, returnStdout: true).trim()
}

//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  
//////////////////////////////////////////////////   executreRemoteCommands                                  ///////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def removeEmptyLinesFromFile(filePath) {
    sh "grep -v '^\\s*\$' ${filePath} > tempFile && mv tempFile ${filePath}"
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------   
//////////////////////////////////////////////////   Checks for status code of last command, which  //////////////////////////////////////////////////////////////////////////////////////////////////////                         
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def checkNetworkStatus() {
    def status = sh(script: "echo \$?", returnStatus: true)
    if (status == 0) {
        return "Up"
    } 
    else {
            echo "Down"
        return "Down"
}
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------     
//////////////////////////////////////////////////   splits output stored by executeRemoteCommands() using    ////////////////////////////////////////////////////////////////////////////////////////////                      
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
//def splitLogOutput(commandOutput, lowerDomain) {
//    def outputs = commandOutput.split(splitOutputVariable) // ---DELIMITER[12]---
//    def error   = outputs.size() > 1 ? outputs[1]?.trim() : ''
//    def summary = outputs.size() > 2 ? outputs[2]?.trim() : ''
//    return [arrayList]
//}
def splitLogOutput(commandOutput, delimiter, lowerDomain) {
    def splitOutput = commandOutput.split("/${delimiter}/")               // Split the commandOutput by the given delimiter and store in an array
    def noWhitespaceOutput = splitOutput.findAll { it.trim() }     // Only keep elements that are non-empty after trimming. Remove everything else
    def finalArray = noWhitespaceOutput.collect { it.trim() } // Trim any whitespace
    return finalArray
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   BELOW THESE COMMENTS START ALL FUNCTION DEFINITIONS!!  ////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def handleError(lowerDomain, errorMessage) {
    sh(script: """
    echo "${lowerDomain} - ERROR: ${errorMessage}" >> 'error.log'
    """)
    error "Terminating pipeline due to unexpected error: ${errorMessage}"
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   getLineCount(filePath) - Provide this function         ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getLineCount(filePath) {
    return sh(script: "wc -l < ${filePath}", returnStdout: true).trim()
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------     
//////////////////////////////////////////////////   getSiteStatuses(lowerDomain, summaryHTMLBody) - Provide this function   ///////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getSiteStatuses(lowerDomain, summaryLogFile) {
    def siteAvailableStatus = sh (
        script: "awk '/${lowerDomain}/ && /Server Availability:/' 'summary.log' | awk -F: '{print \$3}'",
        //script: "grep 'Server Availability:' 'summary.log' | awk -F: '{print \$3}'",
        returnStdout: true
    ).trim()
    def siteActiveStatus = sh(
        script: "awk '/${lowerDomain}/ && /Active/ && /Inactive:/' 'summary.log' | awk -F: '{print \$3}'",
         returnStdout: true
    ).trim()

    siteActiveStatus = siteActiveStatus.contains('A') ? "Active" : "Inactive"
    siteAvailableStatus = siteAvailableStatus.contains("Not") ? "Unavailable" : "Available"
    return [siteAvailableStatus: siteAvailableStatus, siteActiveStatus: siteActiveStatus]
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  
/////////////////   generateHtmlErrorRows(errorLogFile, lowerDomain, siteAvailableStatus, siteActiveStatus, networkStatus) - Provide this function   //////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def generateHtmlErrorRows(errorHTMLBody, errorLogFile, lowerDomain, siteAvailableStatus, siteActiveStatus, networkStatus) {
    def htmlRows = errorLogFile.split('\n').collect { errorLine ->
        if (networkStatus != "Up") {
            return "<tr><td>${lowerDomain}</td><td>Unknown</td><td class=\"status-red\">${networkStatus}</td><td>Unknown</td><td>${errorLine}</td></tr>"
        } 
        else 
            if (siteAvailableStatus && !siteAvailableStatus.contains("Unavailable")) {
                return "<tr><td>${lowerDomain}</td><td>${siteActiveStatus}</td><td class=\"status-green\">${networkStatus}</td><td class=\"status-green\">${siteAvailableStatus}</td><td>${errorLine}</td></tr>"
            } 
            else {
                return "<tr><td>${lowerDomain}</td><td>${siteActiveStatus}</td><td class=\"status-green\">${networkStatus}</td><td class=\"status-red\">${siteAvailableStatus}</td><td>${errorLine}</td></tr>"
            }
    }
    sh(script: "echo '${htmlRows.join('\n').replace("'", "\\'")}' >> ${errorHTMLBody}")
}

//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   sortFileContents(filePath) - Provide this function     ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def sortFileContents(filePath) {
    def lines = readFile(filePath).readLines()           // Read the file into a list of lines
    def sortedLines = lines.sort()                      // Sort the lines
    def fileContent = sortedLines.join('\n') + '\n'    // Write the sorted lines back to the same file
    writeFile(file: filePath, text: fileContent)      // Write the sorted lines back to the file
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  
//////////////////////////////////////////////////   sendEmailWithAttachments(emailAddress, emailSubject) - send the     ///////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def sendEmailWithAttachments(emailAddress, emailSubject,HTMLReport) {
    sh """
    (
        echo "Subject: ${emailSubject}"
        echo "To: ${emailAddress}"
        echo "MIME-Version: 1.0"
        echo "Content-Type: text/html; charset=UTF-8"
        echo "Content-Transfer-Encoding: 7bit"
        echo ""
        cat ${HTMLReport}
        echo '</body></html>'
    ) | /usr/sbin/sendmail -t
    """
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   buildBasicDocument() - Append formatted HTML to         ////////////////////////////////////////////////////////////////////////////////////////////                        
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def buildBasicDocument(errorLog, summaryLog, HTMLReport, lineCount) {
    sh """
        IFS=\$'\\n'
cat << EOF > upper.html
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <title>Basic Report</title>
        </head>
        <body>
        <font size="+2"><b>Checkout Errors: ${lineCount}</b></font>
        <pre>
        <hr size="10" noshade>
        <font size="+1">
EOF
cat << EOF > lower.html
        </font>
        </pre>
        <font size="+2"><b>Checkout Summary</b></font>
        <hr size="10" noshade>
EOF
        cat 'upper.html' ${errorLog} 'lower.html' ${summaryLog} > ${HTMLReport}
    """
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   buildPrettyDocument() - Append formatted HTML to         ////////////////////////////////////////////////////////////////////////////////////////////                           
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def buildPrettyDocument(errorLog,summaryLog, errorLineCount, summaryLineCount, HtmlDocumentHead, HtmlDocumentCenter,HTMLReport) {
    sh """
    IFS=\$'\\n'
cat <<EOF > ${HtmlDocumentHead}
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="UTF-8">
        <title>Email Template</title>
        <style>
        table {width: 100%; border-collapse: collapse;margin: 5px 0;}
        th, td {padding: 1px;text-align: left;border: 1px solid #dddddd;font-size: 95%;}
        th {background-color: #f2f2f2;}
        .site-count {display: flex; justify-content: end; float: right;}
        .status-black {color: black;}
        .status-red {color: red;}
        .status-green {color: green;}
        pre {display: block;font-size: 95%;font-family: Ariel;white-space: pre;}
        </style>
        </head>
        <body>
        <h3><i class="site-count">Checkout Errors: ${errorLineCount}</i></h3>
        <table>
        <tr>
        <th>Site</th>
        <th>Status</th>
        <th>Network</th>
        <th>Availability</th>
        <th>Message</th>
        </tr>
EOF

cat <<EOF > ${HtmlDocumentCenter}
        </table>
        <br>
        <hr>
        <h3>Checkout Summary -- Total Sites Processed: ${summaryLineCount}</h3>
        <hr>
        
EOF
        cat ${HtmlDocumentHead} ${errorLog} ${HtmlDocumentCenter} ${summaryLog} > ${completeHTMLReport}
    """
}
