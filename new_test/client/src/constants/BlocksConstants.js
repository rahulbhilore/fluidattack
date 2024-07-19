import { supportedApps } from "../stores/UserInfoStore";

export const constantPrefix = "BLOCK_";

// Get libraries
export const GET_BLOCK_LIBRARIES = `${constantPrefix}GET_BLOCK_LIBRARIES`;
export const GET_BLOCK_LIBRARIES_SUCCESS = `${constantPrefix}GET_BLOCK_LIBRARIES_SUCCESS`;
export const GET_BLOCK_LIBRARIES_FAIL = `${constantPrefix}GET_BLOCK_LIBRARIES_FAIL`;

// Create library
export const CREATE_BLOCK_LIBRARY = `${constantPrefix}CREATE_BLOCK_LIBRARY`;
export const CREATE_BLOCK_LIBRARY_SUCCESS = `${constantPrefix}CREATE_BLOCK_LIBRARY_SUCCESS`;
export const CREATE_BLOCK_LIBRARY_FAIL = `${constantPrefix}CREATE_BLOCK_LIBRARY_FAIL`;

// Delete multiple libraries
export const DELETE_LIBRARIES = `${constantPrefix}DELETE_LIBRARIES`;
export const DELETE_LIBRARIES_SUCCESS = `${constantPrefix}DELETE_LIBRARIES_SUCCESS`;
export const DELETE_LIBRARIES_FAIL = `${constantPrefix}DELETE_LIBRARIES_FAIL`;

// Get library content
export const GET_BLOCK_LIBRARY_CONTENT = `${constantPrefix}GET_BLOCK_LIBRARY_CONTENT`;
export const GET_BLOCK_LIBRARY_CONTENT_SUCCESS = `${constantPrefix}GET_BLOCK_LIBRARY_CONTENT_SUCCESS`;
export const GET_BLOCK_LIBRARY_CONTENT_FAIL = `${constantPrefix}GET_BLOCK_LIBRARY_CONTENT_FAIL`;

// Get library info
export const GET_BLOCK_LIBRARY_INFO = `${constantPrefix}GET_BLOCK_LIBRARY_INFO`;
export const GET_BLOCK_LIBRARY_INFO_SUCCESS = `${constantPrefix}GET_BLOCK_LIBRARY_INFO_SUCCESS`;
export const GET_BLOCK_LIBRARY_INFO_FAIL = `${constantPrefix}GET_BLOCK_LIBRARY_INFO_FAIL`;

// Upload block
export const UPLOAD_BLOCK = `${constantPrefix}UPLOAD_BLOCK`;
export const UPLOAD_BLOCK_SUCCESS = `${constantPrefix}UPLOAD_BLOCK_SUCCESS`;
export const UPLOAD_BLOCK_FAIL = `${constantPrefix}UPLOAD_BLOCK_FAIL`;

// Upload multiple blocks
export const UPLOAD_BLOCKS = `${constantPrefix}UPLOAD_BLOCKS`;
export const UPLOAD_BLOCKS_SUCCESS = `${constantPrefix}UPLOAD_BLOCKS_SUCCESS`;
export const UPLOAD_BLOCKS_FAIL = `${constantPrefix}UPLOAD_BLOCKS_FAIL`;

// Delete multiple blocks
export const DELETE_BLOCKS = `${constantPrefix}DELETE_BLOCKS`;
export const DELETE_BLOCKS_SUCCESS = `${constantPrefix}DELETE_BLOCKS_SUCCESS`;
export const DELETE_BLOCKS_FAIL = `${constantPrefix}DELETE_BLOCKS_FAIL`;

// Update block
export const UPDATE_BLOCK = `${constantPrefix}UPDATE_BLOCK`;
export const UPDATE_BLOCK_SUCCESS = `${constantPrefix}UPDATE_BLOCK_SUCCESS`;
export const UPDATE_BLOCK_FAIL = `${constantPrefix}UPDATE_BLOCK_FAIL`;

// Update block library
export const UPDATE_BLOCK_LIBRARY = `${constantPrefix}UPDATE_BLOCK_LIBRARY`;
export const UPDATE_BLOCK_LIBRARY_SUCCESS = `${constantPrefix}UPDATE_BLOCK_LIBRARY_SUCCESS`;
export const UPDATE_BLOCK_LIBRARY_FAIL = `${constantPrefix}UPDATE_BLOCK_LIBRARY_FAIL`;

// Download block
export const DOWNLOAD_BLOCK = `${constantPrefix}DOWNLOAD_BLOCK`;
export const DOWNLOAD_BLOCK_SUCCESS = `${constantPrefix}DOWNLOAD_BLOCK_SUCCESS`;
export const DOWNLOAD_BLOCK_FAIL = `${constantPrefix}DOWNLOAD_BLOCK_FAIL`;

// Get block info
export const GET_BLOCK_INFO = `${constantPrefix}GET_BLOCK_INFO`;
export const GET_BLOCK_INFO_SUCCESS = `${constantPrefix}GET_BLOCK_INFO_SUCCESS`;
export const GET_BLOCK_INFO_FAIL = `${constantPrefix}GET_BLOCK_INFO_FAIL`;

// Share block library
export const SHARE_BLOCK_LIBRARY = `${constantPrefix}SHARE_BLOCK_LIBRARY`;
export const SHARE_BLOCK_LIBRARY_SUCCESS = `${constantPrefix}SHARE_BLOCK_LIBRARY_SUCCESS`;
export const SHARE_BLOCK_LIBRARY_FAIL = `${constantPrefix}SHARE_BLOCK_LIBRARY_FAIL`;

// Share block
export const SHARE_BLOCK = `${constantPrefix}SHARE_BLOCK`;
export const SHARE_BLOCK_SUCCESS = `${constantPrefix}SHARE_BLOCK_SUCCESS`;
export const SHARE_BLOCK_FAIL = `${constantPrefix}SHARE_BLOCK_FAIL`;

// Remove share block library
export const REMOVE_SHARE_BLOCK_LIBRARY = `${constantPrefix}REMOVE_SHARE_BLOCK_LIBRARY`;
export const REMOVE_SHARE_BLOCK_LIBRARY_SUCCESS = `${constantPrefix}REMOVE_SHARE_BLOCK_LIBRARY_SUCCESS`;
export const REMOVE_SHARE_BLOCK_LIBRARY_FAIL = `${constantPrefix}REMOVE_SHARE_BLOCK_LIBRARY_FAIL`;

// Remove share block
export const REMOVE_SHARE_BLOCK = `${constantPrefix}REMOVE_SHARE_BLOCK`;
export const REMOVE_SHARE_BLOCK_SUCCESS = `${constantPrefix}REMOVE_SHARE_BLOCK_SUCCESS`;
export const REMOVE_SHARE_BLOCK_FAIL = `${constantPrefix}REMOVE_SHARE_BLOCK_FAIL`;

// Search
export const SEARCH_BLOCKS = `${constantPrefix}SEARCH_BLOCKS`;
export const SEARCH_BLOCKS_SUCCESS = `${constantPrefix}SEARCH_BLOCKS_SUCCESS`;
export const SEARCH_BLOCKS_FAIL = `${constantPrefix}SEARCH_BLOCKS_FAIL`;

export const SUPPORTED_EXTENSIONS = [...supportedApps.xenon].concat("flx");
