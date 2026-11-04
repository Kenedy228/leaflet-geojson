import { mount } from "svelte";
import "./theme.css";
import App from "./App.svelte";
import "leaflet/dist/leaflet.css";

const target = document.getElementById("app");
if (!target) throw new Error("Application container is missing");
mount(App, { target });
