#!groovy
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
//////////////////////////////////////////////////   INFO: Parallel stages in Jenkins scripted pipelines    //////////////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////   INFO: Function definitions are declared in FILENAME: mFunctions.groovy   //////////////////////////////////////////////////////////////////////////
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
/*  
NOTE: sh() in Jenkins waits for command/s to complete, so there's no need for process.waitFor().
----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
TODO:   
    ADD: Timeouts 
    ADD: More Comments
    ADD: 3rd ???  stage('Generate Reports') 
    ADD: DEBUGGING OPTION
    ADD: Parameter sendEmail to configuration
----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
*/

pipeline {
    agent any
    environment {
    /* Global Environment Variables: Pretty much anything that will 
        ever need editing is in the ENVIRONMENT BLOCK below. */
        testServers = /SITE1|SITE2|SITE3|SITE4|SITE5/               // Define test servers to pass to Function getServerList()
        errorLogParams = "N N N NA NA NA No 15"                     // ERROR_LOG are pulled ONLY; No other test will be performed.
        pathToServerList = 'servers.txt'    // Host should be split by whitespace. list should contain fully qualified domain names and IP addresses.
	      remoteUserName = 'USER'                                    // SSH and Rsync(encrypted) privileged username
        delimiter = '---DELIMITER---'                               // Delimiter is placed between echoed output '/tmp/ScriptErrors.log' AND '/tmp/CheckScript.log', so they can be split later.
        scriptLocation = 'remoteScript.sh'   // Path of script that will be copied
        errorFilePath ='/tmp/Errors.log'                      // Remote Site Log path
        checkoutFilePath = '/tmp/Script.log'                   // Remote Site Log path 
    }
    options { buildDiscarder(logRotator(numToKeepStr: '4')) }
    stages {
                                       ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        stage('Build List of Sites') { /////////////////////////////////////////////////////////////////// STAGE ONE /////////////////////////////////////////////////////////////////////////////////////////////////////
                                       ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
            steps {
                script {
                    sshagent(credentials: ['SSH_Key']) {
                        def counter = 0                                                                                                   // keep track of number of sites successfully processed
                        def serverListBatchSize = 16                                                                                      // Controls # of SITES that are proces sed in parallel 
                        def mFunctions = load './mFunctions.groovy'                                                                       // FUNCTION_LOAD: Loads mFunctions.groovy, so now this script can use those functions.
                        mFunctions.createNewFiles('summary.html','error.log','summary.log', 'error.html')                                 // FUNCTION: Ensure log/html files are clear
                        mFunctions.echoConsoleMessage("'Agent Assigned to Job' completed successfully")                                   // FUNCTION: print message in Jenkins Console. Used for debugging
                        def bashParametersAsString = mFunctions.storeBashParametersAsString(params.CHECKOUT_OPTIONS)                      /* FUNCTION: Check for changed default values: ---> */
                        def serverList = mFunctions.getServerList( pathToServerList, testServers, params.DESTINATIONS, params.DRY_RUN )   // FUNCTION: Grabs servers  from servers.txt
                        def serverListSplitIntoBatches = serverList.collate(serverListBatchSize)                                          // Separate hosts into batches and prepare to loop through each
                        def echoServerList = serverList.join('\n')                                                                        // Following 2 LINES were created to ECHO the host -- to be processed -- directly 
                        mFunctions.echoConsoleMessage("Following Host Will Be Processed: \n${echoServerList}")                            //      to Jenkins log console. *** DEBUGGING MUST BE ENABLED !!
                        mFunctions.echoConsoleMessage("Stage 'Build List of Sites' completed successfully")                               // FUNCTION: print message in Jenkins Console. Used for debugging
                        
                        
                                         ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        stage('Run Checkout on Sites') { //////////////////////////////////////////////////////////////// STAGE TWO /////////////////////////////////////////////////////////////////////////////////////////////////////
                                         ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
                serverListSplitIntoBatches.each { batchOfServers ->                     // Iterate each mapping of servers in batches e.g. if batch size is 16 and there are 400 server.
                    parallel batchOfServers.collectEntries { serverLine ->              // (PART--1) Iterate mapping of a single server batch, transforming serverLine into a unique key-value pair in a map.
                        def serverDetails = mFunctions.tokenizeServerLine(serverLine)   // FUNCTION: Splits string into tokens; delimiter is whitespace
                        def lowerDomain = serverDetails.lowerDomain                     // Stores tokenizeServerLine returned values in defined variables
                        def upperDomain = serverDetails.upperDomain                     // Stores tokenizeServerLine returned values in defined variables
                        def consumesOPF = serverDetails.consumesOPF                     // Stores tokenizeServerLine returned values in defined variables
                ["${lowerDomain}": { /* (PART--2)  Successfully Mapped a single site (e.g. SITE1) to everything after 
                                        ["${lowerDomain}":, creating a KEY|VALUE pair for each site and commands performed. */
                    try {
                        def (networkStatus, remoteCommandOutput) = mFunctions.executeRemoteCommands(          // FUNCTION: Execute remote commands on Sites
                                                                                        remoteUserName, 	// Variable: Priveleged Account
                                                                                        lowerDomain,            // Variable: SubDomain for site (e.g. SITE1)
                                                                                        upperDomain,            // Variable: Second/Top Level Domain (e.g. new.old.org)
                                                                                        scriptLocation,         // Variable: Bash script to run on remote servers
                                                                                        bashParametersAsString, // Variable: List of Parameters used as arguments for Bash script
                                                                                        delimiter,              // Variable: Listed in Environment Variables, used to split remote output
                                                                                        errorFilePath,          // Variable: Error Logs from remote site
                                                                                        checkoutFilePath )      // Variable: General logs from remote site
                        if (networkStatus != 'Up') {                                                            /* IF networkStatus contains anything other than 'Up', which should only be down. */
                            sh(script: "echo '${lowerDomain} - ERROR: networkStatus NOT 'Up' >> error.log")
                        } else {                                                                                                            /* ELSE if networkStatus contains anything other than 'Up'. */
                            def (errorMessage, summaryMessage) = mFunctions.splitLogOutput( remoteCommandOutput, delimiter, lowerDomain )   // FUNCTION: splits output, stores in files and variable 
                            sh(script: "echo \"${summaryMessage}\" >> summary.log")                                                         // Stores Summary into log file
                            sh(script: "echo \"<div style='font-size: 16px;'><pre>${summaryMessage}</pre></div><br>\" >> summary.html")     // Stores Summary in a file with HTML Tags
                            if (errorMessage.size() > 0) {                                                                                  /* IF SITE (E.g. SITE1) has ERRORS:, then write lines in errorMessage to error.log 
                                                                                                                                                UPDATE NEEDED: Create Jenkins parameter or Environment variable for filenames. */
                                sh(script: "echo \"${errorMessage}\" >> error.log") /* Stores ERRORS into log file */
                            }
                        } //counter++                                               /* Increment is the final step, indicating a host didn't fail within the TRY/CATCH block */
                    } catch (Exception e) {                                         /* If anything goes really wrong in the above loop, then the catch block grabs it and sends some arguments to the handleError Function. */
                        mFunctions.handleError(lowerDomain, e.message)              /* FUNCTION: Writes exactly ONE row on ERROR table for site. */
                                    }
                                }]
                            }
                        }
                    }
                mFunctions.echoConsoleMessage("Stage 'Run Checkout on Sites' completed successfully") /* FUNCTION: print message in Jenkins Console. Used for debugging */
                    }
                }
            }
        }
    }
}
 post {
        always {
                script {
                        try { /* This Block does some line counting, file fomatting and combining
                                HTML (Outlook is picky about missing HTML tags) & SENDING OUT THE EMAIL */
             		    HTMLReport = 'HTMLReport.html'                                                                            // Complete HTML File Name
                            def mFunctions = load './mFunctions.groovy'                                                       // Must Load mFunction.groovy Again for POST block.
                            def emailAddresses = params.EMAIL_ADDRESS.split('\n').collect { it.trim() }.finSITE { it }        // Address Parameter 
                            def formatEmailAddresses = emailAddresses.join(',')                                               // Address Parameter 
                            def errorLineCount = mFunctions.getLineCount('error.log')                                         // FUNCTION: Get error Count
                            if (params.sendEmail != 'Yes') {                                                                  // Send Email ??
                                mFunctions.echoConsoleMessage("No Email Will Be Sent.")                                       // FUNCTION: print message in Jenkins Console.
                            } else {
                                if (params.HTML_FORMAT == 'Pretty') {                                                         // IF Parameter HTML_FORMAT Contains 'Pretty' continue.
                                    mFunctions.echoConsoleMessage("Creating 'Pretty' HTML Document")                          // FUNCTION: print message in Jenkins Console.
                                    /*
                                    mFunctions.buildPrettyDocument(                                                           // Wraps lowerDomain,ERRORS and other status in HTML Tags
                                                                errorLog,                                                     // Error go into Table
                                                                summaryLog,                                                   // Goes in Summary section
                                                                errorLineCount,                                               // How many errors?
                                                                summaryLineCount,                                             // How many sites successfully processed. Should always be 366 unless something was decomm.
                                                                HTMLReport )                                                  // Completed report that will be shown in email
                                    */
                                } else if (params.HTML_FORMAT == 'Basic') {                                                   // Basic HTML structure with just preformatted text
                                    mFunctions.echoConsoleMessage("Creating 'Basic' HTML Document")                           // FUNCTION: print message in Jenkins Console.
                                    mFunctions.buildBasicDocument( 'error.log' ,'summary.html' ,HTMLReport , errorLineCount ) // FUNCTION: Build Basic HTML File
                                } else {
                                    mFunctions.echoConsoleMessage("${type}. Please specify either 'Basic' or 'Pretty'.")      // FUNCTION: print message in Jenkins Console.
                                }   
				mFunctions.sendEmailWithAttachments(formatEmailAddresses, params.EMAIL_SUBJECT,HTMLReport)                            // Function: sendEmailWithAttachments(recipients, subject, body)
                            } 
				cleanWs()                                                                                                             // clean workspace
                        } catch (Exception e) {                                                                               // Catch block 
                            mFunctions.handleError("Post Block", e.message)                                                   // FUNCTION: Writes exactly ONE row on ERROR table for site.
                        }
                  }    
            }
      }
}
