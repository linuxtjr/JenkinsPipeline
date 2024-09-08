<p>This report is a solution I developed using <strong>Parallel Stages</strong> in Jenkins scripted pipelines, significantly improving build run times.
The environment utilizing this script has approximately 400 servers.</p>
<hr>
<p>Here is a comparison of average runtimes:</p>
<table>
  <tr>
      <th>Current Run time</th>
      <th>Previous Run time</th>
  </tr>
  <tr>
      <th><strong>4 minutes</strong></th>
      <th>30-40 Minutes</th>
  </tr>
</table>
<hr>
<p>The previous script had several issues, such as:</p>
<ul>
  <li>Code duplication (copy/pasting instead of using functions)</li>
  <li>No error handling</li>
  <li>Lack of text formatting</li>
  <li>False negatives in the results</li>
</ul>

<p>By addressing these problems and optimizing the pipeline, the new solution is faster, more reliable, 
 excel ready and the code is easier to read (Work In Progress).</p>

<hr>
<h3>Example Report</h3>
<hr>
<img src="images/example_email.png" alt="Example Table" style="max-width: 100%; height: auto;">
