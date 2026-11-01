import js from "@eslint/js";
import globals from "globals";

// The current app uses classic scripts loaded into one shared browser scope.
const shared = Object.fromEntries(
  [
    "pointsControl",
    "selectionPoints",
    "deletePointControl",
    "L",
    "map",
    "serverURL",
    "accessState",
    "pendingPointWrites",
    "renderAccess",
    "refreshAccess",
    "pointWrite",
    "apiErrorMessage",
    "pointsMarkers",
    "addPointToLayer",
    "showPointsRelatedControls",
    "hidePointsRelatedControls",
    "roadsMarkers",
    "showRoadsRelatedControls",
    "hideRoadsRelatedControls",
    "polygonsMarkers",
    "showPolygonsRelatedControls",
    "hidePolygonsRelatedControls",
    "inSelectionMode",
    "selectedMarkers",
    "distanceLayerGroup",
    "renderSelectedPointsList",
    "pointToDelete",
    "addPointMode",
    "newMarkers",
    "renderNewPointInList",
    "setDraftName",
    "setDraftDisabled",
  ].map((name) => [name, "writable"]),
);

export default [
  { ignores: ["node_modules/**", "dist/**"] },
  js.configs.recommended,
  {
    files: ["*.js", "tests/**/*.cjs"],
    languageOptions: { globals: { ...globals.browser, ...shared } },
    rules: {
      "no-redeclare": ["error", { builtinGlobals: false }],
      "no-unused-vars": [
        "error",
        { vars: "local", args: "none", caughtErrors: "none" },
      ],
    },
  },
  { files: ["*.js"], languageOptions: { sourceType: "script" } },
  {
    files: ["tests/**/*.cjs", "*.mjs"],
    languageOptions: { globals: globals.node },
  },
];
