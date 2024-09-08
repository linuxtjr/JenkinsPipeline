#!groovy
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
//////////////////////////////////////////////////   INFO: Parallel stages in Jenkins scripted pipelines            //////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   INFO: Function definitions are declared at bottom of page      //////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
// TODO: Add Timeouts, Finish Commenting/Notes, Add 3rd stage('Rerun Checkout on Error Sites')
pipeline {
    agent any   // previously: agent { label 'Weblogic' }
    environment { // Global Environment Variables 
        def dryRunOnTestServers = 'Y'                                     // 'N' to run on Production  AND  'Y' to run on Non-Production Sites
        int serverListBatchSize = 16                                      // Controls # of SITES that are proces sed in parallel
        def pathToServerList = 'SERVER_LIST_TXT'                          // Host should be split by whitespace. list should contain fully qualified domain names and IP addresses.
        def testServerRegex = /SERVER1|SERVER2|SERVER3|SERVER4|SERVER5/   // Define test servers to pass to Function getServerList()
        def emailSubject = 'EMAIL_SUBJECT'                                // Add receipeint email address for report and subject line  
        def emailAddress = 'EMAIL_ADDRESSES_HERE'
      
        // Variables Needed for Remote Host /////
        def remoteUserName = 'REMOTE_USERNAME'        // SSH and Rsync(encrypted) privileged username
        def scriptRemote = '/tmp/DIR_NAME_HERE'       // Path to store script on remote server
        def githubScriptPath = './DIR_NAME_HERE'      // Path of script that will be copied
        def errorLogPathRemotePath ='/tmp/ERROR.log'  // Log path on remote host
        def summaryLogRemotePath = '/tmp/SUMMARY.log' // Log path on remote host

        // Local Logs and Files Stored on Jenkins Node ////
        def errorLog = 'ERROR.log'
        def summaryLog = 'SUMMARY.log'
        def errorHTMLBody = 'ERROR.html' 
        def summaryHTMLBody = 'SUMMARY.html' 
    }
    options { buildDiscarder(logRotator(numToKeepStr: '3')) }
    stages {
        stage('Build List of Sites') {
            steps {
                script {
                    echoConsoleMessage("'Agent Assigned to Job' completed successfully")                      // FUNCTION: print message in Jenkins Console. Used for debugging
                    sshagent(credentials: ['SSH_Key']) {                                                      //       
                      def serverList = getServerList(testServerRegex, pathToServerList, dryRunOnTestServers)  // FUNCTION: pulls list of HOSTS and stores in variable.
                      def serverListSplitIntoBatches = serverList.collate(serverListBatchSize)                // Separate hosts into batches and prepare to loop through each
                      clearFiles(checkoutDetail,checkoutError,checkoutError)                                  // FUNCTION: Ensure log/html files are clear
                      echoConsoleMessage("Stage 'Build List of Sites' completed successfully")                // FUNCTION: print message in Jenkins Console. Used for debugging
        stage('Run Checkout on Sites') {
            serverListSplitIntoBatches.each { batchOfServers ->                                               // Iterate each mapping of servers in batches e.g. if batch size is 16 and there are 400 server.
                parallel batchOfServers.collectEntries { serverLine ->                                        // (PART-ONE) Iterate mapping of a single server batch, transforming serverLine into a unique key-value pair in a map.
                    def serverDetails = tokenizeServerLine(serverLine)                                        // FUNCTION: Splits string into tokens; delimiter is whitespace
                    def (lowerDomain, upperDomain, consumesOPF) = [serverDetails.lowerDomain, serverDetails.upperDomain, serverDetails.consumesOPF] // Stores tokenizeServerLine returned values in defined variables
                ["${lowerDomain}": {                                                                          // (PART-TWO) Mapping of a single site e.g. SERVER1 to everything after braces,creating a KEY|VALUE pair for each site and commands performed.
                    try {
                        def remoteCommandOutput = executeRemoteCommands(remoteUserName, lowerDomain, upperDomain, scriptLocal, scriptRemote, consumesOPF) // FUNCTION: Execute remote commands on Sites
                        def networkStatus = checkNetworkStatus()                                                                           // **IMPORTANT** Function checkNetworkStatus() MUST come after remoteCommnandOuput().
                                                                                                                                           // checkNetworkStatus() function grabs the exit code and runs a check!
                        if (networkStatus != 'Up') {
                            errorLog = 'ERROR: Something went wrong while using remote commands SCP/SSH. Check Network'                   // This assumes errorLog is empty because networkStatus check failed                                                 
                            generateHtmlErrorRows(errorHTMLBody, errorLog, lowerDomain, 'Unknown', 'Unknown', networkStatus)              // FUNCTION: Generates exactly ONE row for site in ERROR section.
                        } else {
                            def tempLog = splitAndStoreLogOutput(remoteCommandOutput, lowerDomain)                                        // FUNCTION: splits output, stores in files and variable
                             def (errorLog, summaryLog) = [tempLog.errorLog, tempLog.summaryLog]                                          // Store returned saveOutput() 
                            
                            if (errorLog.size() > 0) {
                                wrapChunkOfTextInHtmlDivTags(summaryLog,summaryHTMLBody)
                                def siteStatuses = getSiteStatuses(lowerDomain, summaryLog)                                                                                  // FUNCTION: Get site availability and active/inactive status
                                generateHtmlErrorRows(errorHTMLBody, errorLog, lowerDomain, siteStatuses.siteAvailableStatus, siteStatuses.siteActiveStatus, networkStatus)  // FUNCTION: Create ERROR rows for site.
                            }
                        }
                     } catch (Exception e) {
                        handleError(lowerDomain, e.message)                             // FUNCTION: Writes exactly ONE row on ERROR table for site.
                                }
                            }]
                        }
                    }
                }
            echoConsoleMessage("Stage 'Run Checkout on Sites' completed successfully")  // FUNCTION: print message in Jenkins Console. Used for debugging
                }
            }
        }
    }
}
 post {
        always {
                script {
                        //DOING A BIT OF LINE COUNTING, FORMATTING / COMBINING HTML (Outlook is picky about missing HTML tags) & SENDING OUT THE EMAIL
                        def topOfHtmlBody = 'top.html',  middleOfHtmlBody = 'middle.html', bottomOfHtmlBody = 'bottom.html', completeHTMLReport = 'completeHTMLReport.html'
                        def errorLineCount = getLineCount(errorCheckOfHtmlBody)                           // FUNCTION: Gets the number of ERRROR lines in the file
                        def summaryLineCount = getUniqLineCount(summaryLog)         // FUNCTION: Gets the number of sites processed by grabbing first column, sorting sites, then remove duplicates.

                        //def attachments = ["FILE.csv", "FILE.pdf", "IMAGE.png"]                 // Attach something to the email. separate additional filenames with a comma.
                        sortFileContents(errorCheckOfHtmlBody)  // Sort error_check.html because the contents coulld be out of order due to parallel execution completion times
                        buildAndCombineHtmlBody(errorLineCount, summaryLineCount,topOfHtmlBody,middleOfHtmlBody,bottomOfHtmlBody,summaryHTMLBody,errorCheckOfHtmlBody,completeHTMLReport) // FUNCTION: creates HTML FILE
                        sendEmailWithAttachments(emailAddress, emailSubject,completeHTMLReport) // Function: sendEmailWithAttachments(recipients, subject, body, attachments)
                        cleanWs()
                  }    
            }
      }
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   getServerList() - pulls the list of sites and returns result   ////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getServerList(testServerRegex, filePath, dryRunOnTestServers) {
    return readFile(filePath)
        .readLines()
        .findAll { 
            if (dryRunOnTestServers == 'N') {
                !(it =~ ${testServerRegex}) // PRODUCTION: Exclude Test Servers
            } else {
                 it =~ ${testServerRegex}  // NON-PRODUCTION: Use this for Test servers
            }
        }
        //.take(10) // UNCOMMENT to limit number of servers for testing, e.g., .take(30) to process only 30 servers.
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
//////////////////////////////////////////////////   clearFiles()  - Clear existing log files  /////////////////////////////////////////////////////////////////////////////////////////////////////////// 
//////////////////////////////////////////////////   can accept any number of string literals  ///////////////////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   and variables as arguments.               ///////////////////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def clearFiles(String... files) {
    files.each { file ->
        sh """
        true > ${file}
        """
    }
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   Splits output  //////////////////////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def tokenizeServerLine(serverLine) {
    def parts = serverLine.tokenize()
    if (parts.size() >= 2) {
        def lowerDomain = parts[0]
        def upperDomain = parts[1]
        def consumesOPF = parts[5]
        return [lowerDomain: lowerDomain, upperDomain: upperDomain, consumesOPF: consumesOPF]
    } else {
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
//////////////////////////////////////////////////   executreRemoteCommands                                  ///////////////////////////////////////////////////////////////////////////////////////////               
//////////////////////////////////////////////////   executreRemoteCommands                                  ///////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def executeRemoteCommands(remoteUserName,lowerDomain, upperDomain, scriptLocal, scriptRemote, consumesOPF) {
    def command = """
        ##scp -p -o StrictHostKeyChecking=no ${scriptLocal} ${remoteUserName}@${lowerDomain}${upperDomain}:/tmp/ && \\
        rsync -avh --update -e "ssh -o StrictHostKeyChecking=no" ${scriptLocal} ${remoteUserName}@${lowerDomain}${upperDomain}:/tmp/ && \\
        ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${remoteUserName}@${lowerDomain}${upperDomain} \\
        "chmod 755 ${scriptRemote} && nohup ${scriptRemote} ${consumesOPF} >/dev/null 2>&1; \\
        echo '---OUTPUT1---'; cat /tmp/ERROR.log; \\
        echo '---OUTPUT2---'; cat /tmp/SUMMARY.log"
    """
    return sh(script: command, returnStdout: true).trim()
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------   
//////////////////////////////////////////////////   Checks for status code of last command, which  //////////////////////////////////////////////////////////////////////////////////////////////////////               
//////////////////////////////////////////////////   comes from Function executeRemoteCommands      //////////////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def checkNetworkStatus() {
    def status = sh(script: "echo \$?", returnStatus: true)
    return (status == 0) ? "Up" : "Down"
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------     
//////////////////////////////////////////////////   splits output stored by executeRemoteCommands() using    ////////////////////////////////////////////////////////////////////////////////////////////               
//////////////////////////////////////////////////   delimiters ---OUTPUT1--- and ---OUTPUT2---, then stores  ////////////////////////////////////////////////////////////////////////////////////////////               
//////////////////////////////////////////////////   each into separate variables and summary body of report  ////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def splitAndReturnLogOutput(commandOutput, lowerDomain) {
    def outputs = commandOutput.split(/---OUTPUT[12]---/)
    def errorLog = outputs.size() > 1 ? outputs[1]?.trim() : ''
    def summaryLog = outputs.size() > 2 ? outputs[2]?.trim() : ''
    return [errorLog: errorLog, summaryLog: summaryLog]
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   BELOW THESE COMMENTS START ALL FUNCTION DEFINITIONS!!  ////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def handleError(lowerDomain, errorMessage) {
    sh(script: """
    echo "<tr><td>${lowerDomain}</td><td></td>Unknown<td></td>Unknown<td>ERROR: ${errorMessage}</td></tr>" >> ${errorCheckOfHtmlBody}
    """)
    error "Terminating pipeline due to unexpected error: ${errorMessage}"
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   getLineCount(filePath) - Provide this function         ////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   with a file and the function returns a line count      ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getLineCount(filePath) {
    return sh(script: "wc -l < ${filePath}", returnStdout: true).trim()
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////  getUniqLineCount():  ////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getUniqLineCount(filePath) {
    return sh(script: "awk '{print \$1}' ${filePath} | sort | uniq | wc -l", returnStdout: true).trim()
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------     
//////////////////////////////////////////////////   getSiteStatuses(lowerDomain, summaryHTMLBody) - Provide this function   ///////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////    with a lowerDomain (e.g. STPL1 or WOOD1) and the function returns      ///////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////    ERROR COlUMNS "Availability" and "Status"                              ///////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def getSiteStatuses(lowerDomain, summaryLog) {
    def siteAvailableStatus = sh (
        script: "awk '/${lowerDomain}/ && /Server Availability:/' ${summaryLog} | awk -F: '{print \$3}'",
        //script: "grep 'Server Availability:' '${summaryHTMLBody}' | awk -F: '{print \$3}'",
        returnStdout: true
    ).trim()
    def siteActiveStatus = sh(
        script: "awk '/${lowerDomain}/ && /Active/ && /Inactive:/' ${summaryLog} | awk -F: '{print \$3}'",
         returnStdout: true
    ).trim()

    siteActiveStatus = siteActiveStatus.contains('A') ? "Active" : "Inactive"
    siteAvailableStatus = siteAvailableStatus.contains("Not") ? "Unavailable" : "Available"
    return [siteAvailableStatus: siteAvailableStatus, siteActiveStatus: siteActiveStatus]
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  
/////////////////   generateHtmlErrorRows(errorLog, lowerDomain, siteAvailableStatus, siteActiveStatus, networkStatus) - Provide this function   //////////////////////////////////////////////////////
/////////////////   generateHtmlErrorRows(errorLog, lowerDomain, siteAvailableStatus, siteActiveStatus, networkStatus) - Provide this function   //////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def generateHtmlErrorRows(errorHTMLBody, errorLog, lowerDomain, siteAvailableStatus, siteActiveStatus, networkStatus) {
    def htmlRows = errorLog.split('\n').collect { errorLine ->
        if (networkStatus != "Up") {
            return "<tr><td>${lowerDomain}</td><td>Unknown</td><td class=\"status-red\">${networkStatus}</td><td>Unknown</td><td>${errorLine}</td></tr>"
        } else 
            if (siteAvailableStatus && !siteAvailableStatus.contains("Unavailable")) {
                return "<tr><td>${lowerDomain}</td><td>${siteActiveStatus}</td><td class=\"status-green\">${networkStatus}</td><td class=\"status-green\">${siteAvailableStatus}</td><td>${errorLine}</td></tr>"
            } else {
                return "<tr><td>${lowerDomain}</td><td>${siteActiveStatus}</td><td class=\"status-green\">${networkStatus}</td><td class=\"status-red\">${siteAvailableStatus}</td><td>${errorLine}</td></tr>"
            }
    }
    sh(script: "echo '${htmlRows.join('\n').replace("'", "\\'")}' >> ${errorHTMLBody}")
}

//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   sortFileContents(filePath) - Provide this function     ////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   with a file and sort the lines in the file             ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def wrapChunkOfTextInHtmlDivTags(summaryLog,summaryHTMLBody) {
    // Append output to HTML File
    sh(script: "echo \"${summaryLog}\" >> ${summaryHTMLBody}")
    sh(script: "echo \"<hr><div><pre>${summaryLog}</pre></div><br>\" >> ${summaryHTMLBody}")
}

//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------    
//////////////////////////////////////////////////   sortFileContents(filePath) - Provide this function     ////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   with a file and sort the lines in the file             ////////////////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def sortFileContents(filePath) {
    def lines = readFile(filePath).readLines()           // Read the file into a list of lines
    def sortedLines = lines.sort()                      // Sort the lines
    def fileContent = sortedLines.join('\n') + '\n'    // Write the sorted lines back to the same file
    writeFile(file: filePath, text: fileContent)      // Write the sorted lines back to the file
}
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  
//////////////////////////////////////////////////   sendEmailWithAttachments(emailAddress, emailSubject) - send the     ///////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   email using the generated HTML files                               ////////////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def sendEmailWithAttachments(emailAddress, emailSubject, bottomOfHtmlBody) {
    sh """
    (
        echo "Subject: ${emailSubject}"
        echo "To: ${emailAddress}"
        echo "MIME-Version: 1.0"
        echo "Content-Type: text/html; charset=UTF-8"
        echo "Content-Transfer-Encoding: 7bit"
        echo ""
        cat ${completeHTMLReport}
        ##uuencode template.csv
        cat ${bottomOfHtmlBody}
    ) | /usr/sbin/sendmail -t
    """
}
//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- 
//////////////////////////////////////////////////   buildHtmlBody() - Append formatted HTML to         ////////////////////////////////////////////////////////////////////////////////////////////             
//////////////////////////////////////////////////   begin.html, center.html and end.html                   ////////////////////////////////////////////////////////////////////////////////////////////               
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
def buildAndCombineHtmlBody(errorLineCount, summaryLineCount, middleOfHtmlBody, topOfHtmlBody,summaryHTMLBody,errorCheckOfHtmlBody,completeHTMLReport) {
    sh """
    IFS=\$'\\n'
cat <<EOF > ${topOfHtmlBody}
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <title>Email Template</title>
        <style>
            /* Add basic styling for the table */
            table {
                width: 100%;
                border-collapse: collapse;
                margin: 5px 0;
            }
            th, td {
                padding: 1px;
                text-align: left;
                border: 1px solid #dddddd;
                font-size: 95%;
            }
            th {
                background-color: #f2f2f2;
            }
            .site-count {
                  display: flex; 
                  justify-content: end; 
                  float: right;
            }
            .status-black {
                  color: black;
            }
            .status-red {
                  color: red;
            }
            .status-green {
                  color: green;
            }
            pre {
                  display: block;
                  font-size: 95%;
                  font-family: Ariel;
                  white-space: pre;
            }
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

cat <<EOF > ${middleOfHtmlBody}
        </table>
        <br>
        <hr>
        <h3>Checkout Summary -- Total Sites Processed: ${summaryLineCount}</h3>
            <hr>
        
EOF

cat <<EOF > ${bottomOfHtmlBody}
    </body>
    </html>
EOF
    ##########################################################################################################################################
    ######## Concatenate the entire HTML body from the generated parts #######################################################################
    ##########################################################################################################################################
    cat ${topOfHtmlBody} ${errorCheckOfHtmlBody} ${middleOfHtmlBody} ${summaryHTMLBody} > ${completeHTMLReport}
    """
}
