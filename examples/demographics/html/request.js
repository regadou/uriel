document.addEventListener("DOMContentLoaded", init);

function init() { 
   document.querySelector("#url").value = document.location; 
   document.querySelector("#view").addEventListener("click", async function() {
      document.querySelector("#data").value = await fileSelection();
   });
   document.querySelector("#action").addEventListener("click", async function() {
      const url = document.querySelector("#url").value;
      const method = document.querySelector("#method").value.toUpperCase();
      const headers = getHeaders(method);
      if (!headers)
         return alert("No headers provided");         
      switch (method) {
         case "POST":
         case "PUT":
         case "PATCH":
            const body = document.querySelector("#file").files[0] ? (await fileSelection()) : document.querySelector("#data").value;
            return fetch(url, {method:method, headers:headers, body:body}).then(requestResult).catch(requestError);
         default:
            return fetch(url, {method:method, headers:headers}).then(requestResult).catch(requestError);
      }
   });
}

async function fileSelection() {
    return new Promise((resolve, reject) => {
        try {
            const file = document.querySelector("#file").files[0];
            if (file) {
                document.querySelector("#format").value = headers["content-type"] = file.type;
                const reader = new FileReader();  
                reader.onload = function(e) { resolve(e.target.result); };
                reader.readAsBinaryString(file);
            }
        }
        catch (e) { reject(e); }
    });
}

function getHeaders(method) {
   var headers = {}
   try {
      var lines = document.querySelector("#headers").value.trim().split("\n");
      for (var i = 0; i < lines.length; i++) {
         var line = lines[i].trim();
         if (line == "")
            continue;
         var dp = line.indexOf(':');
         if (dp < 0)
            dp = line.length;
         headers[line.substring(0,dp).trim().toLowerCase()] = line.substring(dp+1).trim();
      }
   }
   catch (e) { 
      alert("Invalid headers:\n"+e+"\n"+lines);
      return null;
   }
   if (method != "GET" && method != "DELETE" && headers["content-type"] == null) {
      var type = document.querySelector("#format").value.trim();
      if (type)
         headers["content-type"] = type;
   }
   return headers;
}

async function requestResult(response) {
   var status = "status: "+response.status+" "+response.statusText+"<br>\n";
   response.headers.forEach((v, k) => status += k+": "+v+"<br>\n");
   document.querySelector("#response").innerHTML = status; 
   var type = response.headers.get("content-type");
   if (type == null)
      type = "text/plain";
   else
      type = type.split(";")[0]
   const html = formatResult(await response.text(), type);
   document.querySelector("#result").innerHTML = html; 
}

function formatResult(txt, type) {
   switch (type) {
      case "text/html":
      case "application/xhtml":
         return txt;
      case "text/csv":
         const lines = txt.trim().split("\n");
         var html = "";
         for (var i = 0; i < lines.length; i++) {
            var values = parseLine(lines[i], i+1);
            if (values == null)
               continue;
            html += "<tr>";
            for (var v in values) {
               var value = escapeHtml(values[v]);
               html += i ? "<td>"+value+"</td>" : "<th>"+value+"</th>";
            }
            html += "</tr>\n";
         }
         return "<table border=1>\n"+html+"</table>";
      case "application/json":
      case "application/ld+json":
         return "<pre>" + escapeHtml(JSON.stringify(JSON.parse(txt), null, 4)) + "</pre>";
      case "text/xml":
      case "application/xml":
      default:
         return "<pre>" + escapeHtml(txt) + "</pre>";
   }
}

function parseLine(txt, lineno) {
   if (txt == null)
      return null;
   txt = txt.trim();
   if (!txt)
      return null;
   const values = [];
   var value = null;
   var quote = null;
   for (var i = 0; i < txt.length; i++) {
      var c = txt.charAt(i);
      if (!quote) {
         switch (c) {
            case ',':
               values.push((value == null) ? "" : value.trim());
               value = "";
               break;
            case '"':
            case "'":
               if (value)
                  value += c;
               else {
                  quote = c;
                  value = "";
               }
               break;
            default:
               if (value != null)
                  value += c;
               else if (c > ' ')
                  value = c;
         }        
      }
      else if (c == quote) {
         var next = txt.charAt(i+1) || '';
         switch (next) {
            case '':
            case ',':
               values.push(value);
               value = quote = null;
               i++;
               break;
            case "'":
            case '"':
               if (next == quote) {
                  value += quote;
                  i++;
                  break;
               }
            default:
               throw new Error("Parsing error at line "+lineno+": "+value+next);
         }
      }
      else
         value += c;
   }
   if (value != null)
      values.push(quote ? value : value.trim());
   return values;
}

function escapeHtml(src) {
   return src ? src.toString().replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;") : "";
}

function requestError(error) {
   document.querySelector("#result").innerHTML = "<pre>Request error: "+error+"</pre>";
}

