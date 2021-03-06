/*******************************************************************************
 * Copyright 2014-2017 Specure GmbH
 * Copyright 2013-2015 alladin-IT GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package at.specure.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.net.ssl.SSLContext;

import at.specure.net.measurementlab.ndt.NdtTests;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.json.JSONException;
import org.json.JSONObject;

import at.specure.client.helper.Config;
import at.specure.client.helper.RevisionHelper;
import at.specure.client.helper.TestStatus;
import at.specure.client.ndt.NDTRunner;
import at.specure.client.v2.task.QoSTestEnum;
import at.specure.client.v2.task.result.QoSResultCollector;
import at.specure.client.v2.task.service.TestSettings;

public class RMBTClientRunner
{
    
    /**
     * @param args
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException
     */
    public static void main(final String[] args) throws IOException, InterruptedException, KeyManagementException,
            NoSuchAlgorithmException
    {
        final OptionParser parser = new OptionParser()
        {
            {
                acceptsAll(Arrays.asList("?", "help"), "show help");
                
                acceptsAll(Arrays.asList("h", "host"), "RMBT server IP or hostname (required)").withRequiredArg()
                        .ofType(String.class);
                
                acceptsAll(Arrays.asList("p", "port"), "RMBT server port (required)").withRequiredArg().ofType(
                        Integer.class);
                
                acceptsAll(Arrays.asList("s", "ssl"), "use SSL/TLS");
                
                acceptsAll(Arrays.asList("ssl-no-verify"), "turn off SSL/TLS certificate validation");
                
                acceptsAll(Arrays.asList("t", "threads"), "number of threads (required when dev-mode)")
                        .withRequiredArg().ofType(Integer.class);
                
                acceptsAll(Arrays.asList("d", "duration"), "test duration in seconds (required when dev-mode)")
                        .withRequiredArg().ofType(Integer.class);
                
                acceptsAll(Arrays.asList("n", "ndt"), "run NDT after RMBT");
                
                acceptsAll(Arrays.asList("ndt-host"), "NDT host to use").withRequiredArg()
                        .ofType(String.class);
                
            }
        };
        
        System.out.println(String.format("=============== TestClient %s ===============",
                RevisionHelper.getVerboseRevision()));
        
        OptionSet options;
        try
        {
            options = parser.parse(args);
        }
        catch (final OptionException e)
        {
            System.out.println(String.format("error while parsing command line options: %s", e.getLocalizedMessage()));
            System.exit(1);
            return;
        }
        
        final String[] requiredArgs = { "h", "p" };
        
        if (options.has("ssl-no-verify"))
            SSLContext.setDefault(TestClient.getSSLContext(null, null));
        else
            SSLContext.setDefault(TestClient.getSSLContext("at/specure/crt/ca.pem",
                    "at/specure/crt/controlserver.pem"));
        
        boolean reqArgMissing = false;
        if (!options.has("?"))
            for (final String arg : requiredArgs)
                if (!options.has(arg))
                {
                    reqArgMissing = true;
                    System.out.println(String.format("ERROR: required argument '%s' is missing", arg));
                }
        if (options.has("?") || reqArgMissing)
        {
            System.out.println();
            parser.printHelpOn(System.out);
            System.exit(1);
            return;
        }
        
        final TestClient client;
        
        final String host = (String) options.valueOf("h");
        final int port = (Integer) options.valueOf("p");
        final boolean encryption = options.has("s") ? true : false;
        
        final ArrayList<String> geoInfo = null;
        
        final String uuid = "1cc2d6bb-2f07-4cb8-8fd6-fb5ffcf10cb0";
        
        final JSONObject additionalValues = new JSONObject();
        try
        {
            additionalValues.put("ndt", options.has("n"));
            additionalValues.put("plattform", "CLI");
        }
        catch (JSONException e)
        {
            e.printStackTrace();
        }
        
        
        int numThreads = 0;
        int duration = 0;
        if (options.has("t"))
            numThreads = (Integer) options.valueOf("t");
        if (options.has("d"))
            duration = (Integer) options.valueOf("d");
        
        int numPings = 10;
        
        TestParameter overrideParams = null;
        if (numThreads > 0 || duration > 0)
            overrideParams = new TestParameter(null, 0, false, duration, numThreads, numPings);
            
        client = TestClient.getInstance(host, null, port, -1, encryption, geoInfo, uuid,
                "DESKTOP", Config.RMBT_CLIENT_NAME, Config.RMBT_VERSION_NUMBER, overrideParams, null, null);
        
        if (client != null)
        {
            final TestResult result = client.runTest();
            
            if (result != null)
            {
                final JsonObject jsonResult = new JsonObject();
                try
                {
                    Gson gson = new Gson();
                    jsonResult.add("network_type", gson.toJsonTree("97"));
                    jsonResult.add("plattform", gson.toJsonTree("CLI"));
                }
                catch (JsonParseException e)
                {
                    e.printStackTrace();
                }
                client.sendResult(jsonResult);
            }
            
            client.shutdown();
            
			try {
				System.out.print("Starting QoS Test... ");
            	TestSettings nnTestSettings = new TestSettings(client.getControlConnection().getStartTimeNs());
				QualityOfServiceTest nnTest = new QualityOfServiceTest(client, nnTestSettings);
				QoSResultCollector nnResult = nnTest.call();
            	System.out.println("finished.");
				if (nnResult != null && nnTest.getStatus().equals(QoSTestEnum.QOS_FINISHED)) {
					System.out.print("Sending QoS results... ");
					client.sendQoSResult(nnResult);
					System.out.println("finished");
				}
				else {
					System.out.println("Error during QoS test.");
				}
            	
			} catch (Exception e) {
				e.printStackTrace();
			}                            	                    	

                       
            if (client.getStatus() != TestStatus.END)
                System.out.println("ERROR: " + client.getErrorMsg());
            else
            {
                if (options.has("n"))
                {
                    System.out.println("\n\nStarting NDT...");
                    
                    String ndtHost = null;
                    if (options.has("ndt-host"))
                        ndtHost = (String) options.valueOf("ndt-host");
                    
                    final NDTRunner ndtRunner = new NDTRunner(ndtHost);
                    ndtRunner.runNDT(NdtTests.NETWORK_WIRED, ndtRunner.new UiServices()
                    {
                        @Override
                        public void appendString(String str, int viewId)
                        {
                            super.appendString(str, viewId);
//                            if (viewId == MAIN_VIEW)
                            System.out.println(str);
                        }
                        
                        @Override
                        public void sendResults()
                        {
                            System.out.println("sending NDT results...");
                            client.getControlConnection().sendNDTResult(this, null);
                        }
                    });
                    
                    System.out.println("NDT finished.");
                }
            }
        }
        
    }
    
}
