/* eslint-disable no-console */
import fs from "fs";

const finalObject = {};
fs.readdirSync(".").forEach(fileName => {
  if (fileName.includes(".svg")) {
    const formattedName = fileName.substr(0, fileName.indexOf(".svg"));
    const storageNamePart = formattedName.substr(0, formattedName.indexOf("-"));
    if (formattedName.includes("-active")) {
      finalObject[`${storageNamePart}ActiveSVG`] = `${storageNamePart}Active`;
    } else if (formattedName.includes("-inactive-white")) {
      finalObject[
        `${storageNamePart}InactiveWhiteSVG`
      ] = `${storageNamePart}InactiveWhite`;
    } else if (formattedName.includes("-inactive-grey")) {
      finalObject[
        `${storageNamePart}InactiveGreySVG`
      ] = `${storageNamePart}InactiveGrey`;
    } else if (formattedName.includes("-inactive")) {
      finalObject[
        `${storageNamePart}InactiveSVG`
      ] = `${storageNamePart}Inactive`;
    } else {
      finalObject[`${formattedName}SVG`] = formattedName;
    }
  }
});

let imports = "";

fs.readdirSync(".").forEach(fileName => {
  if (fileName.includes(".svg")) {
    const formattedName = fileName.substr(0, fileName.indexOf(".svg"));
    const storageNamePart = formattedName.substr(0, formattedName.indexOf("-"));
    if (formattedName.includes("-active")) {
      imports += `import ${storageNamePart}ActiveSVG from '../../assets/images/storages/${fileName}'\n`;
    } else if (formattedName.includes("-inactive-white")) {
      imports += `import ${storageNamePart}InactiveWhiteSVG from '../../assets/images/storages/${fileName}'\n`;
    } else if (formattedName.includes("-inactive-grey")) {
      imports += `import ${storageNamePart}InactiveGreySVG from '../../assets/images/storages/${fileName}'\n`;
    } else if (formattedName.includes("-inactive")) {
      imports += `import ${storageNamePart}InactiveSVG from '../../assets/images/storages/${fileName}'\n`;
    } else {
      imports += `import ${formattedName}SVG from '../../assets/images/storages/${fileName}'\n`;
    }
  }
});

console.info(finalObject);
console.info(imports);
