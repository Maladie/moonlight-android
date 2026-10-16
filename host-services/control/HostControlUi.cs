using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using Microsoft.Win32;

namespace MoonWaker.HostControl
{
    internal enum Tone { Ok, Warn, Error, Off, Accent }

    internal enum ButtonKind { Normal, Primary, Danger }

    // Colours of the accepted mock-up (.claude/handoffs/mockups/host-control): dark by default,
    // light when Windows apps use the light theme.
    internal sealed class Palette
    {
        internal readonly bool Light;
        internal readonly SolidColorBrush Background, Side, Card, Card2, Line, Text, Muted, Accent, AccentSoft,
            Ok, OkSoft, Warn, WarnSoft, Error, ErrorSoft, Hover, White;

        internal Palette(bool light)
        {
            Light = light;
            Background = Brush(light ? "#F3F3F8" : "#1B1B22");
            Side = Brush(light ? "#EAEAF2" : "#15151B");
            Card = Brush(light ? "#FFFFFF" : "#24242D");
            Card2 = Brush(light ? "#F0F0F6" : "#2C2C37");
            Line = Brush(light ? "#DCDCE6" : "#363643");
            Text = Brush(light ? "#1C1C24" : "#F2F2F7");
            Muted = Brush(light ? "#686878" : "#A0A0B2");
            Accent = Brush(light ? "#6554F0" : "#7464FF");
            AccentSoft = Brush(light ? "#1A6554F0" : "#297464FF");
            Ok = Brush(light ? "#1F9D63" : "#4CC38A");
            OkSoft = Brush(light ? "#1A1F9D63" : "#244CC38A");
            Warn = Brush(light ? "#A8730A" : "#E8B94A");
            WarnSoft = Brush(light ? "#21C88C0A" : "#24E8B94A");
            Error = Brush(light ? "#C93A3A" : "#F26D6D");
            ErrorSoft = Brush(light ? "#1AC93A3A" : "#24F26D6D");
            Hover = Brush(light ? "#0F000000" : "#14FFFFFF");
            White = Brush("#FFFFFF");
        }

        internal SolidColorBrush Strong(Tone tone)
        {
            return tone == Tone.Ok ? Ok : tone == Tone.Warn ? Warn : tone == Tone.Error ? Error
                : tone == Tone.Accent ? Accent : Muted;
        }

        internal SolidColorBrush Soft(Tone tone)
        {
            return tone == Tone.Ok ? OkSoft : tone == Tone.Warn ? WarnSoft : tone == Tone.Error ? ErrorSoft
                : tone == Tone.Accent ? AccentSoft : Card2;
        }

        internal static SolidColorBrush Brush(string hex)
        {
            SolidColorBrush brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
            brush.Freeze();
            return brush;
        }

        // HKCU AppsUseLightTheme, read only; dark when it is missing or unreadable.
        internal static bool WindowsAppsUseLightTheme()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", false))
                {
                    object value = key == null ? null : key.GetValue("AppsUseLightTheme");
                    return value is int && (int)value != 0;
                }
            }
            catch { return false; }
        }
    }

    // The window's building blocks, in the mock-up's sizes; every colour comes from the palette.
    internal sealed class Ui
    {
        internal static readonly FontFamily Font = new FontFamily("Segoe UI Variable Text, Segoe UI");
        internal readonly Palette P;
        private readonly ControlTemplate button8, button10;
        private readonly Style focus;

        internal Ui(Palette palette)
        {
            P = palette;
            button8 = ButtonTemplate(8);
            button10 = ButtonTemplate(10);
            FrameworkElementFactory ring = new FrameworkElementFactory(typeof(Rectangle));
            ring.SetValue(Shape.StrokeProperty, P.Accent);
            ring.SetValue(Shape.StrokeThicknessProperty, 2.0);
            ring.SetValue(Rectangle.RadiusXProperty, 10.0);
            ring.SetValue(Rectangle.RadiusYProperty, 10.0);
            ring.SetValue(FrameworkElement.MarginProperty, new Thickness(-3));
            ring.SetValue(UIElement.SnapsToDevicePixelsProperty, true);
            ControlTemplate ringTemplate = new ControlTemplate(typeof(Control));
            ringTemplate.VisualTree = ring;
            focus = new Style();
            focus.Setters.Add(new Setter(Control.TemplateProperty, ringTemplate));
        }

        // Chrome = background and border; a hover layer; the content inside Padding.
        private ControlTemplate ButtonTemplate(double radius)
        {
            FrameworkElementFactory chrome = new FrameworkElementFactory(typeof(Border), "Chrome");
            chrome.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius));
            chrome.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
            chrome.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Control.BorderBrushProperty));
            chrome.SetValue(Border.BorderThicknessProperty, new TemplateBindingExtension(Control.BorderThicknessProperty));
            chrome.SetValue(UIElement.SnapsToDevicePixelsProperty, true);
            FrameworkElementFactory layers = new FrameworkElementFactory(typeof(Grid));
            FrameworkElementFactory hover = new FrameworkElementFactory(typeof(Border), "Hover");
            hover.SetValue(Border.CornerRadiusProperty, new CornerRadius(radius - 1));
            hover.SetValue(Border.BackgroundProperty, P.Hover);
            hover.SetValue(UIElement.OpacityProperty, 0.0);
            FrameworkElementFactory content = new FrameworkElementFactory(typeof(ContentPresenter));
            content.SetValue(FrameworkElement.MarginProperty, new TemplateBindingExtension(Control.PaddingProperty));
            content.SetValue(FrameworkElement.HorizontalAlignmentProperty,
                new TemplateBindingExtension(Control.HorizontalContentAlignmentProperty));
            content.SetValue(FrameworkElement.VerticalAlignmentProperty,
                new TemplateBindingExtension(Control.VerticalContentAlignmentProperty));
            layers.AppendChild(hover);
            layers.AppendChild(content);
            chrome.AppendChild(layers);
            ControlTemplate template = new ControlTemplate(typeof(Button));
            template.VisualTree = chrome;
            Trigger over = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
            over.Setters.Add(new Setter(UIElement.OpacityProperty, 1.0, "Hover"));
            Trigger pressed = new Trigger { Property = ButtonBase.IsPressedProperty, Value = true };
            pressed.Setters.Add(new Setter(UIElement.OpacityProperty, 0.8, "Chrome"));
            Trigger disabled = new Trigger { Property = UIElement.IsEnabledProperty, Value = false };
            disabled.Setters.Add(new Setter(UIElement.OpacityProperty, 0.45));
            template.Triggers.Add(over);
            template.Triggers.Add(pressed);
            template.Triggers.Add(disabled);
            return template;
        }

        internal TextBlock Text(string text, double size, Brush brush, FontWeight weight)
        {
            TextBlock block = new TextBlock();
            block.Text = text;
            block.FontSize = size;
            block.Foreground = brush;
            block.FontWeight = weight;
            block.TextWrapping = TextWrapping.Wrap;
            block.VerticalAlignment = VerticalAlignment.Center;
            return block;
        }

        internal TextBlock Text(string text) { return Text(text, 13, P.Text, FontWeights.Normal); }
        internal TextBlock Bold(string text, double size) { return Text(text, size, P.Text, FontWeights.SemiBold); }
        internal TextBlock Muted(string text) { return Text(text, 12, P.Muted, FontWeights.Normal); }

        // A bold title followed by a muted note on one line.
        internal TextBlock Titled(string title, string note)
        {
            TextBlock block = Bold(title, 13);
            if (!String.IsNullOrEmpty(note))
                block.Inlines.Add(new Run(note) { FontWeight = FontWeights.Normal, Foreground = P.Muted, FontSize = 12 });
            return block;
        }

        // id: the automation id that focus is restored to after a re-render; defaults to the text.
        internal Button Button(string text, ButtonKind kind, bool small, bool elevated, Action click, string id = null)
        {
            Button button = new Button();
            button.Template = button8;
            button.FocusVisualStyle = focus;
            button.Height = small ? 28 : 32;
            button.Padding = new Thickness(small ? 11 : 14, 0, small ? 11 : 14, 0);
            button.FontSize = small ? 12 : 12.5;
            button.BorderThickness = new Thickness(1);
            button.Cursor = Cursors.Hand;
            button.HorizontalAlignment = HorizontalAlignment.Left;
            button.VerticalAlignment = VerticalAlignment.Center;
            button.Background = kind == ButtonKind.Primary ? P.Accent
                : kind == ButtonKind.Danger ? Brushes.Transparent : P.Card2;
            button.BorderBrush = kind == ButtonKind.Primary ? P.Accent : kind == ButtonKind.Danger ? P.Error : P.Line;
            button.Foreground = kind == ButtonKind.Primary ? P.White : kind == ButtonKind.Danger ? P.Error : P.Text;
            if (kind == ButtonKind.Primary) button.FontWeight = FontWeights.SemiBold;
            StackPanel content = new StackPanel { Orientation = Orientation.Horizontal };
            if (elevated)
            {
                FrameworkElement shield = Shield();
                shield.Margin = new Thickness(0, 0, 7, 0);
                content.Children.Add(shield);
            }
            content.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });
            button.Content = content;
            AutomationProperties.SetName(button, elevated ? text + " (wymaga zgody administratora)" : text);
            AutomationProperties.SetAutomationId(button, id ?? text);
            if (elevated) button.ToolTip = "Zapis wymaga zgody administratora (UAC).";
            button.Click += delegate { click(); };
            return button;
        }

        // A card-like button: navigation entries and profile cards.
        internal Button Tile(UIElement content, Brush background, Brush border, string name, Action click)
        {
            Button button = new Button();
            button.Template = button10;
            button.FocusVisualStyle = focus;
            button.Content = content;
            button.Background = background;
            button.BorderBrush = border;
            button.BorderThickness = new Thickness(1);
            button.HorizontalContentAlignment = HorizontalAlignment.Stretch;
            button.VerticalContentAlignment = VerticalAlignment.Center;
            button.Cursor = Cursors.Hand;
            AutomationProperties.SetName(button, name);
            AutomationProperties.SetAutomationId(button, name);
            button.Click += delegate { click(); };
            return button;
        }

        internal Border Card(UIElement child)
        {
            return new Border {
                Background = P.Card, BorderBrush = P.Line, BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(12), Padding = new Thickness(16, 14, 16, 14), Child = child };
        }

        // A card heading with an optional element on the right.
        internal DockPanel Heading(string title, UIElement right, UIElement afterTitle = null)
        {
            DockPanel line = new DockPanel { Margin = new Thickness(0, 0, 0, 10), LastChildFill = false };
            TextBlock text = Bold(title, 14);
            line.Children.Add(text);
            if (afterTitle != null)
            {
                FrameworkElement element = (FrameworkElement)afterTitle;
                element.Margin = new Thickness(8, 0, 0, 0);
                line.Children.Add(afterTitle);
            }
            if (right != null)
            {
                DockPanel.SetDock(right, Dock.Right);
                line.Children.Add(right);
            }
            return line;
        }

        internal Border Pill(string text, Tone tone)
        {
            TextBlock label = Text(text, 11.5, P.Strong(tone), FontWeights.SemiBold);
            label.TextWrapping = TextWrapping.NoWrap;
            return new Border {
                Background = P.Soft(tone), CornerRadius = new CornerRadius(11), Height = 22,
                Padding = new Thickness(9, 0, 9, 0), VerticalAlignment = VerticalAlignment.Center, Child = label };
        }

        internal Ellipse Dot(Tone tone)
        {
            return new Ellipse { Width = 9, Height = 9, Fill = P.Strong(tone), VerticalAlignment = VerticalAlignment.Center };
        }

        private static readonly string[] AvatarColours = { "#7464FF", "#2AA198", "#3A6FD8", "#D36F3C", "#B05BD6" };

        // index: the profile's place in the list, so a profile keeps its colour on every page.
        internal Border Avatar(string name, int index, double size)
        {
            string initial = String.IsNullOrWhiteSpace(name) ? "?" : name.Trim().Substring(0, 1).ToUpperInvariant();
            TextBlock letter = Text(initial, Math.Round(size * 0.41), P.White, FontWeights.Bold);
            letter.HorizontalAlignment = HorizontalAlignment.Center;
            return new Border {
                Width = size, Height = size, CornerRadius = new CornerRadius(size / 2),
                Background = Palette.Brush(AvatarColours[Math.Max(0, index) % AvatarColours.Length]),
                VerticalAlignment = VerticalAlignment.Center, Child = letter };
        }

        // A row of a list: an optional leading mark, a title over a muted line, trailing items.
        internal Border Item(bool first, UIElement leading, UIElement title, string detail, params UIElement[] trailing)
        {
            DockPanel row = new DockPanel();
            if (leading != null)
            {
                ((FrameworkElement)leading).Margin = new Thickness(0, 0, 12, 0);
                row.Children.Add(leading);
            }
            StackPanel right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            foreach (UIElement element in trailing)
            {
                if (element == null) continue;
                ((FrameworkElement)element).Margin = new Thickness(8, 0, 0, 0);
                right.Children.Add(element);
            }
            DockPanel.SetDock(right, Dock.Right);
            row.Children.Add(right);
            StackPanel text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            text.Children.Add(title);
            if (!String.IsNullOrEmpty(detail))
            {
                TextBlock line = Muted(detail);
                line.Margin = new Thickness(0, 1, 0, 0);
                text.Children.Add(line);
            }
            row.Children.Add(text);
            return new Border {
                BorderBrush = P.Line, BorderThickness = new Thickness(0, first ? 0 : 1, 0, 0),
                MinHeight = 46, Padding = new Thickness(0, 6, 0, 6), Child = row };
        }

        internal Border KeyValue(bool first, string key, UIElement value)
        {
            DockPanel row = new DockPanel();
            DockPanel.SetDock(value, Dock.Right);
            row.Children.Add(value);
            row.Children.Add(Text(key, 13, P.Muted, FontWeights.Normal));
            return new Border {
                BorderBrush = P.Line, BorderThickness = new Thickness(0, first ? 0 : 1, 0, 0),
                Padding = new Thickness(0, 8, 0, 8), Child = row };
        }

        internal Border Banner(Tone tone, string title, string text, UIElement action)
        {
            DockPanel row = new DockPanel();
            if (tone != Tone.Accent)
            {
                Ellipse dot = Dot(tone);
                dot.Margin = new Thickness(0, 0, 12, 0);
                row.Children.Add(dot);
            }
            if (action != null)
            {
                ((FrameworkElement)action).Margin = new Thickness(12, 0, 0, 0);
                DockPanel.SetDock(action, Dock.Right);
                row.Children.Add(action);
            }
            TextBlock message = Text("");
            message.Inlines.Add(new Run(title) { FontWeight = FontWeights.SemiBold });
            if (!String.IsNullOrEmpty(text)) message.Inlines.Add(new Run(" " + text));
            row.Children.Add(message);
            return new Border {
                Background = P.Soft(tone), BorderBrush = P.Strong(tone), BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(10), Padding = new Thickness(14, 8, 14, 8), MinHeight = 46, Child = row };
        }

        // A thin progress bar; Fill sets its fraction.
        internal Grid Bar(double fraction)
        {
            Grid bar = new Grid { Height = 6, VerticalAlignment = VerticalAlignment.Center };
            bar.ColumnDefinitions.Add(new ColumnDefinition());
            bar.ColumnDefinitions.Add(new ColumnDefinition());
            Border track = new Border { Background = P.Card2, CornerRadius = new CornerRadius(3) };
            Grid.SetColumnSpan(track, 2);
            bar.Children.Add(track);
            bar.Children.Add(new Border { Background = P.Accent, CornerRadius = new CornerRadius(3) });
            Fill(bar, fraction);
            return bar;
        }

        internal static void Fill(Grid bar, double fraction)
        {
            fraction = Math.Max(0, Math.Min(1, fraction));
            bar.ColumnDefinitions[0].Width = new GridLength(fraction, GridUnitType.Star);
            bar.ColumnDefinitions[1].Width = new GridLength(1 - fraction, GridUnitType.Star);
        }

        // A switch: the state of a grant, flipped by click, Space or Enter. A muted switch
        // still answers a click, with why it cannot change.
        // changed: an unsaved switch, shown in its new state inside an accent ring.
        internal Button Switch(bool on, string name, bool muted, bool changed, Action click, string id)
        {
            Ellipse knob = new Ellipse {
                Width = 12, Height = 12, Fill = on ? P.White : P.Muted, Margin = new Thickness(3, 0, 3, 0),
                HorizontalAlignment = on ? HorizontalAlignment.Right : HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center };
            Border track = new Border {
                Width = 38, Height = 20, CornerRadius = new CornerRadius(10), BorderThickness = new Thickness(1),
                Background = on ? P.Accent : P.Card2, BorderBrush = on ? P.Accent : P.Line, Child = knob,
                Opacity = muted ? 0.45 : 1 };
            Border ring = new Border {
                CornerRadius = new CornerRadius(13), BorderThickness = new Thickness(2), Padding = new Thickness(1),
                BorderBrush = changed ? P.Accent : Brushes.Transparent, Background = changed ? P.AccentSoft : null,
                Child = track };
            Button button = Tile(ring, Brushes.Transparent, Brushes.Transparent,
                name + (on ? ": tak" : ": nie") + (changed ? " (niezapisana zmiana)" : ""), click);
            button.HorizontalAlignment = HorizontalAlignment.Center;
            button.Padding = new Thickness(2);
            AutomationProperties.SetAutomationId(button, id);
            return button;
        }

        // A 24-unit outline icon (the mock-up's stroke icons) drawn at the given size.
        internal FrameworkElement Icon(string data, Brush brush, double size)
        {
            Path path = new Path {
                Data = Geometry.Parse(data), Stroke = brush, StrokeThickness = 1.8,
                StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round,
                StrokeEndLineCap = PenLineCap.Round };
            Canvas canvas = new Canvas { Width = 24, Height = 24 };
            canvas.Children.Add(path);
            return new Viewbox { Width = size, Height = size, Child = canvas, VerticalAlignment = VerticalAlignment.Center };
        }

        internal FrameworkElement Check(double size)
        {
            FrameworkElement mark = Icon("M20 6L9 17L4 12", P.White, size * 0.55);
            ((Path)((Canvas)((Viewbox)mark).Child).Children[0]).StrokeThickness = 3;
            mark.HorizontalAlignment = HorizontalAlignment.Center;
            return new Border {
                Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = P.Ok,
                VerticalAlignment = VerticalAlignment.Center, Child = mark };
        }

        internal Border Step(int number, bool current)
        {
            TextBlock text = Text(number.ToString(), 11, current ? P.Accent : P.Muted, FontWeights.Bold);
            text.HorizontalAlignment = HorizontalAlignment.Center;
            return new Border {
                Width = 22, Height = 22, CornerRadius = new CornerRadius(11), BorderThickness = new Thickness(1.5),
                BorderBrush = current ? P.Accent : P.Line, VerticalAlignment = VerticalAlignment.Center, Child = text };
        }

        // The UAC shield beside an action that opens the elevated Configurator.
        internal static FrameworkElement Shield()
        {
            DrawingGroup drawing = new DrawingGroup();
            drawing.ClipGeometry = Geometry.Parse("M8 0.8L14.2 3V7.8C14.2 11.2 11.7 13.7 8 15.2C4.3 13.7 1.8 11.2 1.8 7.8V3Z");
            Brush blue = Palette.Brush("#2F80E0"), yellow = Palette.Brush("#F2B632");
            drawing.Children.Add(new GeometryDrawing(blue, null, new RectangleGeometry(new Rect(0, 0, 8, 8))));
            drawing.Children.Add(new GeometryDrawing(yellow, null, new RectangleGeometry(new Rect(8, 0, 8, 8))));
            drawing.Children.Add(new GeometryDrawing(yellow, null, new RectangleGeometry(new Rect(0, 8, 8, 8))));
            drawing.Children.Add(new GeometryDrawing(blue, null, new RectangleGeometry(new Rect(8, 8, 8, 8))));
            drawing.Freeze();
            return new Image {
                Source = new DrawingImage(drawing), Width = 14, Height = 14, Stretch = Stretch.Uniform,
                VerticalAlignment = VerticalAlignment.Center };
        }

        // A thin rounded scroll bar in the palette. Track.Thumb is no child element, so
        // FrameworkElementFactory cannot build it: this one template is XAML.
        internal Style ScrollBarStyle()
        {
            Style style = new Style(typeof(ScrollBar));
            style.Setters.Add(new Setter(FrameworkElement.WidthProperty, 10.0));
            style.Setters.Add(new Setter(FrameworkElement.MinWidthProperty, 10.0));
            style.Setters.Add(new Setter(Control.TemplateProperty, System.Windows.Markup.XamlReader.Parse(
                "<ControlTemplate TargetType='ScrollBar' " +
                "xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' " +
                "xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml'>" +
                "<Track x:Name='PART_Track' IsDirectionReversed='True'><Track.Thumb><Thumb><Thumb.Template>" +
                "<ControlTemplate TargetType='Thumb'><Border CornerRadius='3' Margin='2' Background='" +
                P.Line.Color + "'/></ControlTemplate></Thumb.Template></Thumb></Track.Thumb></Track>" +
                "</ControlTemplate>")));
            return style;
        }

        internal void Input(Control input)
        {
            input.Background = P.Card2;
            input.Foreground = P.Text;
            input.BorderBrush = P.Line;
            input.Padding = new Thickness(8, 6, 8, 6);
            input.FontSize = 13;
            TextBox box = input as TextBox;
            if (box != null) { box.CaretBrush = P.Text; box.SelectionBrush = P.Accent; }
            PasswordBox password = input as PasswordBox;
            if (password != null) { password.CaretBrush = P.Text; password.SelectionBrush = P.Accent; }
        }
    }

    internal static class TitleBar
    {
        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);

        // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 since Windows 10 20H1, 19 before it.
        internal static void Apply(Window window, bool dark)
        {
            IntPtr handle = new WindowInteropHelper(window).Handle;
            if (handle == IntPtr.Zero) return;
            int value = dark ? 1 : 0;
            try
            {
                if (DwmSetWindowAttribute(handle, 20, ref value, sizeof(int)) != 0)
                    DwmSetWindowAttribute(handle, 19, ref value, sizeof(int));
            }
            catch (DllNotFoundException) { }
        }
    }

    // A small modal window in the main window's style: a description, optional fields, an
    // inline validation message, and a confirm button beside "Anuluj" (Enter / Esc; Enter is
    // "Anuluj" when the confirm button is a Danger one). shield: the confirm button saves
    // through the elevated helper (UAC).
    internal sealed class HostDialog : Window
    {
        private readonly TextBlock problem;

        internal HostDialog(Window owner, Ui ui, string title, string description, UIElement body,
            string confirm, ButtonKind kind, Func<string> validate, double width = 500, bool shield = false)
        {
            if (owner != null && owner.IsVisible) Owner = owner;
            WindowStartupLocation = Owner != null ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen;
            Title = title;
            Width = width;
            SizeToContent = SizeToContent.Height;
            ResizeMode = ResizeMode.NoResize;
            ShowInTaskbar = Owner == null;
            Background = ui.P.Background;
            Foreground = ui.P.Text;
            FontFamily = Ui.Font;
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);
            SourceInitialized += delegate { TitleBar.Apply(this, !ui.P.Light); };

            StackPanel panel = new StackPanel { Margin = new Thickness(24, 20, 24, 20) };
            panel.Children.Add(ui.Bold(title, 18));
            if (!String.IsNullOrEmpty(description))
            {
                TextBlock text = ui.Text(description, 13, ui.P.Muted, FontWeights.Normal);
                text.Margin = new Thickness(0, 6, 0, 0);
                panel.Children.Add(text);
            }
            if (body != null)
            {
                ((FrameworkElement)body).Margin = new Thickness(0, 14, 0, 0);
                panel.Children.Add(body);
            }
            problem = ui.Text("", 12.5, ui.P.Error, FontWeights.SemiBold);
            problem.Margin = new Thickness(0, 10, 0, 0);
            problem.Visibility = Visibility.Collapsed;
            panel.Children.Add(problem);
            StackPanel buttons = new StackPanel {
                Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, 18, 0, 0) };
            Button accept = ui.Button(confirm, kind, false, shield, delegate
            {
                string message = validate == null ? null : validate();
                if (message == null) { DialogResult = true; return; }
                problem.Text = message;
                problem.Visibility = Visibility.Visible;
            });
            Button cancel = ui.Button("Anuluj", ButtonKind.Normal, false, false, delegate { DialogResult = false; });
            cancel.IsCancel = true;
            bool danger = kind == ButtonKind.Danger;
            accept.IsDefault = !danger;
            cancel.IsDefault = danger;
            cancel.Margin = new Thickness(8, 0, 0, 0);
            buttons.Children.Add(accept);
            buttons.Children.Add(cancel);
            panel.Children.Add(buttons);
            Content = panel;
            Loaded += delegate
            {
                FrameworkElement first = body as FrameworkElement;
                Control input = first == null ? null : FindInput(first);
                (input ?? (Control)(danger ? cancel : accept)).Focus();
            };
        }

        private static Control FindInput(DependencyObject parent)
        {
            foreach (object child in LogicalTreeHelper.GetChildren(parent))
            {
                if (child is TextBox || child is PasswordBox) return (Control)child;
                DependencyObject element = child as DependencyObject;
                Control found = element == null ? null : FindInput(element);
                if (found != null) return found;
            }
            return null;
        }

        internal static bool Confirm(Window owner, Ui ui, string title, string text, string confirm, bool danger,
            bool shield = false)
        {
            return new HostDialog(owner, ui, title, text, null, confirm,
                danger ? ButtonKind.Danger : ButtonKind.Primary, null, 500, shield).ShowDialog() == true;
        }

        private const string SteamKeyPage = "https://steamcommunity.com/dev/apikey";

        // The 32-hex Steam Web API key, or null when cancelled.
        internal static string SteamKey(Window owner, Ui ui, string profileName)
        {
            StackPanel body = new StackPanel();
            body.Children.Add(ui.Text("32-znakowy klucz Steam Web API", 13, ui.P.Text, FontWeights.Normal));
            PasswordBox key = new PasswordBox { MaxLength = 32, Margin = new Thickness(0, 6, 0, 0),
                FontFamily = new FontFamily("Cascadia Mono, Consolas") };
            ui.Input(key);
            AutomationProperties.SetName(key, "Klucz Steam Web API");
            body.Children.Add(key);
            TextBlock storage = ui.Muted("Klucz jest zapisywany dla tego konta Windows przez DPAPI; Gateway nigdy go nie zwraca, a Host Control go nie pokazuje.");
            storage.Margin = new Thickness(0, 8, 0, 0);
            body.Children.Add(storage);
            TextBlock help = ui.Muted(
                "1. Otwórz stronę rejestracji klucza Steam i zaloguj się na konto, którego bibliotekę ma czytać MoonWaker.\n" +
                "2. W polu Domain Name wpisz localhost, zaakceptuj Steam Web API Terms of Use i zarejestruj klucz.\n" +
                "3. Wklej 32-znakowy klucz powyżej. Nowy klucz zastępuje poprzedni.\n" +
                "MoonWaker wysyła klucz tylko do api.steampowered.com przez HTTPS. Jeśli wycieknie, unieważnij go na tej samej stronie.");
            help.Margin = new Thickness(0, 10, 0, 0);
            help.Visibility = Visibility.Collapsed;
            Button open = ui.Button("Otwórz stronę klucza Steam", ButtonKind.Normal, true, false, delegate
            {
                try { Process.Start(new ProcessStartInfo(SteamKeyPage) { UseShellExecute = true }); }
                catch { }
            });
            open.Margin = new Thickness(0, 8, 0, 0);
            open.Visibility = Visibility.Collapsed;
            Button toggle = null;
            toggle = ui.Button("Jak uzyskać klucz?", ButtonKind.Normal, true, false, delegate
            {
                help.Visibility = open.Visibility = Visibility.Visible;
                toggle.Visibility = Visibility.Collapsed;
                open.Focus();
            });
            toggle.Margin = new Thickness(0, 10, 0, 0);
            body.Children.Add(toggle);
            body.Children.Add(help);
            body.Children.Add(open);
            HostDialog dialog = new HostDialog(owner, ui, "Połącz Steam", "Profil: " + profileName, body,
                "Zapisz klucz", ButtonKind.Primary, delegate
                {
                    return Regex.IsMatch(key.Password.Trim(), "^[A-Fa-f0-9]{32}$") ? null
                        : "Wpisz dokładnie 32 znaki szesnastkowe.";
                });
            string result = dialog.ShowDialog() == true ? key.Password.Trim() : null;
            key.Clear();
            return result;
        }

        // Discord application data; both empty keeps an existing configuration.
        internal static bool Discord(Window owner, Ui ui, string profileName, bool configured,
            out string clientId, out string secret)
        {
            StackPanel body = new StackPanel();
            body.Children.Add(ui.Text("Client ID", 13, ui.P.Text, FontWeights.Normal));
            TextBox id = new TextBox { MaxLength = 20, Margin = new Thickness(0, 6, 0, 10) };
            ui.Input(id);
            AutomationProperties.SetName(id, "Discord Client ID");
            body.Children.Add(id);
            body.Children.Add(ui.Text("Client Secret", 13, ui.P.Text, FontWeights.Normal));
            PasswordBox password = new PasswordBox { Margin = new Thickness(0, 6, 0, 0) };
            ui.Input(password);
            AutomationProperties.SetName(password, "Discord Client Secret");
            body.Children.Add(password);
            TextBlock hint = ui.Muted("Przy pierwszej konfiguracji podaj oba pola. Discord poprosi o autoryzację OAuth na tym komputerze przy pierwszym użyciu z TV.");
            hint.Margin = new Thickness(0, 8, 0, 0);
            body.Children.Add(hint);
            HostDialog dialog = new HostDialog(owner, ui, "Dane aplikacji Discord",
                "Profil: " + profileName + ". " + (configured
                    ? "Discord jest skonfigurowany; puste pola zachowają zapisane dane."
                    : "Sekrety są zapisywane dla tego konta Windows przez DPAPI."),
                body, "Zapisz", ButtonKind.Primary, delegate
                {
                    bool hasId = id.Text.Trim().Length > 0, hasSecret = password.Password.Trim().Length > 0;
                    if (hasId != hasSecret || (!configured && !hasId))
                        return "Podaj jednocześnie Client ID i Client Secret.";
                    if (hasId && !Regex.IsMatch(id.Text.Trim(), "^[0-9]{17,20}$"))
                        return "Discord Client ID musi zawierać od 17 do 20 cyfr.";
                    return null;
                });
            bool saved = dialog.ShowDialog() == true;
            clientId = saved ? id.Text.Trim() : null;
            secret = saved ? password.Password : null;
            password.Clear();
            return saved;
        }
    }
}
