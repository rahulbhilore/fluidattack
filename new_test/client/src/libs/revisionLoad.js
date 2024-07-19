/**
 * Created by khizh on 9/21/2015.
 */
const ua = window.navigator.userAgent;
let version = 11;
const msie = ua.indexOf("MSIE ");
if (msie > 0) {
  version = parseInt(ua.substring(msie + 5, ua.indexOf(".", msie)), 10);
}
// in IE7- JSON isn't supported
if (version <= 7) {
  const loader = document.getElementById("loader");
  loader.parentNode.removeChild(loader);
  const loadingScreenDiv = document.getElementById("loadingScreen");
  loadingScreenDiv.innerHTML =
    "<h4>Sorry, Internet Explorer is not supported. <br/> Please try to use Chrome, Firefox or Safari.</h4>";
  loadingScreenDiv.style.width = "400px";
  loadingScreenDiv.style.height = "300px";
  loadingScreenDiv.style.marginTop = "-150px";
  loadingScreenDiv.style.marginLeft = "-200px";
  loadingScreenDiv.style.top = "50%";
  loadingScreenDiv.style.left = "50%";
  loadingScreenDiv.style.position = "absolute";
} else {
  const folder = "/";
  const xhttp = new XMLHttpRequest();
  xhttp.onreadystatechange = () => {
    if (this.readyState === 4 && this.status === 200) {
      let data = null;
      try {
        data = JSON.parse(this.responseText);
      } catch (exception) {
        // eslint-disable-next-line no-alert
        alert("Invalid configuration file!");
      }
      const path = folder;
      window.ARESKudoConfigObject = data;
      const compScript = document.createElement("script");
      compScript.async = true;
      compScript.type = "text/javascript";
      document.getElementsByTagName("body")[0].appendChild(compScript);
      compScript.src = `${path}js/libraries/compatibility.js`;
    }
  };
  xhttp.open("GET", `${folder}configs/config.json`, true);
  xhttp.send();
}
