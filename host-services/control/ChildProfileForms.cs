using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace MoonWaker.HostConfigurator
{
    // The form edits this small in-memory value and leaves persistence to its owner.
    internal sealed class ChildDayDraft
    {
        internal bool Enabled;
        internal int StartMinute;
        internal int EndMinute;
        internal int DailyLimitSeconds;

        internal ChildDayDraft()
        {
            Enabled = false;
            StartMinute = 0;
            EndMinute = 1440;
            DailyLimitSeconds = 0;
        }

        internal ChildDayDraft Clone()
        {
            return new ChildDayDraft
            {
                Enabled = Enabled,
                StartMinute = StartMinute,
                EndMinute = EndMinute,
                DailyLimitSeconds = DailyLimitSeconds
            };
        }
    }

    internal sealed class ChildProfileDraft
    {
        internal string Name;
        internal string AvatarId;
        internal bool Enabled;
        internal List<ChildDayDraft> Days;
        internal List<string> AllowedGameKeys;

        internal ChildProfileDraft()
        {
            Name = string.Empty;
            AvatarId = string.Empty;
            Enabled = true;
            Days = new List<ChildDayDraft>();
            AllowedGameKeys = new List<string>();
        }

        internal static ChildProfileDraft New()
        {
            ChildProfileDraft draft = new ChildProfileDraft();
            for (int i = 0; i < 7; i++)
            {
                draft.Days.Add(new ChildDayDraft());
            }

            return draft;
        }

        internal ChildProfileDraft Clone()
        {
            ChildProfileDraft clone = new ChildProfileDraft
            {
                Name = Name ?? string.Empty,
                AvatarId = AvatarId ?? string.Empty,
                Enabled = Enabled,
                Days = new List<ChildDayDraft>(),
                AllowedGameKeys = new List<string>()
            };

            if (Days != null)
            {
                foreach (ChildDayDraft day in Days)
                {
                    clone.Days.Add(day == null ? null : day.Clone());
                }
            }

            if (AllowedGameKeys != null)
            {
                foreach (string key in AllowedGameKeys)
                {
                    clone.AllowedGameKeys.Add(key);
                }
            }

            return clone;
        }
    }

    internal sealed class ChildProfileForm : Form
    {
        private const int DayCount = 7;
        private const int MinutesPerDay = 1440;
        private const int MaxDailyLimitMinutes = 1440;

        private static readonly string[] DayNames =
        {
            "Poniedziałek",
            "Wtorek",
            "Środa",
            "Czwartek",
            "Piątek",
            "Sobota",
            "Niedziela"
        };

        private sealed class AvatarOption
        {
            internal readonly string Value;
            private readonly string label;

            internal AvatarOption(string value, string label)
            {
                Value = value;
                this.label = label;
            }

            public override string ToString()
            {
                return label;
            }
        }

        private readonly Action<ChildProfileDraft> saveDraft;
        private ChildProfileDraft draft;
        private readonly TextBox nameBox;
        private readonly ComboBox avatarBox;
        private readonly CheckBox profileEnabledBox;
        private readonly CheckBox[] dayEnabledBoxes = new CheckBox[DayCount];
        private readonly MaskedTextBox[] startBoxes = new MaskedTextBox[DayCount];
        private readonly MaskedTextBox[] endBoxes = new MaskedTextBox[DayCount];
        private readonly NumericUpDown[] limitBoxes = new NumericUpDown[DayCount];
        private readonly Label errorLabel;
        private bool loading;

        internal ChildProfileForm(
            ChildProfileDraft initial,
            Action<ChildProfileDraft> saveDraft,
            string parentProfileName)
        {
            if (initial == null)
            {
                throw new ArgumentNullException("initial");
            }

            if (saveDraft == null)
            {
                throw new ArgumentNullException("saveDraft");
            }

            this.saveDraft = saveDraft;
            draft = initial.Clone();

            Ui.Prepare(this, "Profil dziecka", 980, 760);

            Ui.Label(this, "Profil dziecka", 28, 20, 900, 30, 16, FontStyle.Bold, Color.White);

            Label parentInfo = new Label
            {
                AutoSize = false,
                BackColor = Ui.Panel,
                ForeColor = Ui.Muted,
                Text = BuildParentInfo(parentProfileName),
                TextAlign = ContentAlignment.MiddleLeft,
                Padding = new Padding(10, 4, 10, 4),
                Bounds = new Rectangle(28, 60, 920, 52)
            };
            Controls.Add(parentInfo);

            Ui.Label(this, "Nazwa", 28, 132, 140, 24, 10, FontStyle.Regular, Color.White);
            nameBox = Ui.TextBox(this, 178, 128, 320);

            Ui.Label(this, "Awatar", 530, 132, 135, 24, 10, FontStyle.Regular, Color.White);
            avatarBox = BuildAvatarBox(draft.AvatarId);

            profileEnabledBox = new CheckBox
            {
                AutoSize = true,
                BackColor = Ui.Background,
                ForeColor = Color.White,
                Text = "Profil aktywny",
                Location = new Point(28, 172)
            };
            Controls.Add(profileEnabledBox);

            Ui.Label(
                this,
                "Wybierz jedno okno grania dziennie, np. 08:00–20:00. Limit podajesz w minutach.",
                28,
                204,
                920,
                34,
                9,
                FontStyle.Regular,
                Ui.Muted);

            TableLayoutPanel schedule = BuildScheduleTable();
            schedule.Location = new Point(28, 246);
            schedule.Size = new Size(920, 350);
            Controls.Add(schedule);

            Ui.Label(
                this,
                "Gry udostępnisz osobno w panelu gry rodzica. Po upływie czasu gra zostanie zamknięta — zapisz wcześniej postęp.",
                28,
                610,
                920,
                34,
                9,
                FontStyle.Regular,
                Ui.Muted);

            errorLabel = new Label
            {
                AutoSize = false,
                ForeColor = Ui.Danger,
                BackColor = Ui.Background,
                TextAlign = ContentAlignment.MiddleLeft,
                Bounds = new Rectangle(28, 648, 650, 42)
            };
            Controls.Add(errorLabel);

            Button cancelButton = Ui.Button(this, "Anuluj", 690, 686, 120, delegate
            {
                DialogResult = DialogResult.Cancel;
                Close();
            }, false);
            Button saveButton = Ui.Button(this, "Zapisz", 828, 686, 120, SaveClicked, true);
            CancelButton = cancelButton;
            AcceptButton = saveButton;

            LoadDraft();
        }

        // This is also usable by a non-UI harness and keeps validation deterministic.
        internal static string ValidateDraft(ChildProfileDraft candidate)
        {
            if (candidate == null)
            {
                return "Brak danych profilu.";
            }

            string name = (candidate.Name ?? string.Empty).Trim();
            if (name.Length == 0)
            {
                return "Nazwa profilu jest wymagana.";
            }

            if (name.Length > 80 || ContainsControlCharacter(name))
            {
                return "Nazwa profilu ma nieprawidłowy format.";
            }

            string avatarId = (candidate.AvatarId ?? string.Empty).Trim();
            if (avatarId.Length > 128 || ContainsControlCharacter(avatarId))
            {
                return "Wybierz prawidłowy awatar.";
            }

            if (candidate.Days == null || candidate.Days.Count != DayCount)
            {
                return "Harmonogram musi zawierać siedem dni.";
            }

            for (int i = 0; i < candidate.Days.Count; i++)
            {
                ChildDayDraft day = candidate.Days[i];
                if (day == null)
                {
                    return "Harmonogram zawiera pusty dzień.";
                }

                if (day.StartMinute < 0 || day.StartMinute >= MinutesPerDay ||
                    day.EndMinute <= 0 || day.EndMinute > MinutesPerDay ||
                    day.StartMinute >= day.EndMinute)
                {
                    return "Ustaw godzinę rozpoczęcia wcześniejszą od zakończenia.";
                }

                if (day.DailyLimitSeconds < 0 ||
                    day.DailyLimitSeconds > MaxDailyLimitMinutes * 60 ||
                    day.DailyLimitSeconds % 60 != 0)
                {
                    return "Dzienny limit musi być liczbą pełnych minut od 0 do 1440.";
                }
            }

            return string.Empty;
        }

        private ComboBox BuildAvatarBox(string currentAvatarId)
        {
            string current = (currentAvatarId ?? string.Empty).Trim();
            ComboBox box = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                FlatStyle = FlatStyle.Flat,
                BackColor = Ui.Panel,
                ForeColor = Color.White,
                Bounds = new Rectangle(670, 128, 278, 30)
            };

            AddAvatarOption(box, "default", "Domyślny");
            AddAvatarOption(box, "purple", "Fioletowy");
            AddAvatarOption(box, "blue", "Niebieski");
            AddAvatarOption(box, "green", "Zielony");
            AddAvatarOption(box, "orange", "Pomarańczowy");

            if (current.Length > 0 && FindAvatarOption(box, current) == null)
            {
                box.Items.Add(new AvatarOption(current, "Obecny"));
            }

            AvatarOption selected = FindAvatarOption(box, current);
            if (selected == null)
            {
                selected = FindAvatarOption(box, "default");
            }
            box.SelectedItem = selected;
            Controls.Add(box);
            return box;
        }

        private static void AddAvatarOption(ComboBox box, string value, string label)
        {
            box.Items.Add(new AvatarOption(value, label));
        }

        private static AvatarOption FindAvatarOption(ComboBox box, string value)
        {
            for (int i = 0; i < box.Items.Count; i++)
            {
                AvatarOption option = box.Items[i] as AvatarOption;
                if (option != null && string.Equals(option.Value, value, StringComparison.OrdinalIgnoreCase))
                {
                    return option;
                }
            }

            return null;
        }

        private static bool ContainsControlCharacter(string value)
        {
            foreach (char character in value)
            {
                if (char.IsControl(character))
                {
                    return true;
                }
            }

            return false;
        }

        private static string BuildParentInfo(string parentProfileName)
        {
            string parent = (parentProfileName ?? string.Empty).Trim();
            if (parent.Length == 0)
            {
                parent = "konto Windows rodzica";
            }

            return "Windows rodzica: " + parent + ". Zapisy gier i osiągnięcia są wspólne z tym kontem Windows.";
        }

        private TableLayoutPanel BuildScheduleTable()
        {
            TableLayoutPanel table = new TableLayoutPanel
            {
                BackColor = Ui.Panel,
                ForeColor = Color.White,
                CellBorderStyle = TableLayoutPanelCellBorderStyle.Single,
                ColumnCount = 5,
                RowCount = DayCount + 1,
                Padding = new Padding(4),
                Margin = new Padding(0)
            };

            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 27));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 15));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 19));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 19));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 20));
            table.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
            for (int i = 0; i < DayCount; i++)
            {
                table.RowStyles.Add(new RowStyle(SizeType.Absolute, 43));
            }

            table.Controls.Add(HeaderLabel("Dzień"), 0, 0);
            table.Controls.Add(HeaderLabel("Aktywny"), 1, 0);
            table.Controls.Add(HeaderLabel("Od"), 2, 0);
            table.Controls.Add(HeaderLabel("Do"), 3, 0);
            table.Controls.Add(HeaderLabel("Limit (min)"), 4, 0);

            for (int i = 0; i < DayCount; i++)
            {
                int dayIndex = i;
                table.Controls.Add(RowLabel(DayNames[i]), 0, i + 1);

                CheckBox enabled = new CheckBox
                {
                    AutoSize = true,
                    BackColor = Ui.Panel,
                    ForeColor = Color.White,
                    Text = "włączony",
                    Anchor = AnchorStyles.Left
                };
                enabled.CheckedChanged += delegate { UpdateDayControls(dayIndex); };
                dayEnabledBoxes[i] = enabled;
                table.Controls.Add(enabled, 1, i + 1);

                MaskedTextBox start = TimeBox();
                MaskedTextBox end = TimeBox();
                NumericUpDown limit = NumberBox(MaxDailyLimitMinutes);
                startBoxes[i] = start;
                endBoxes[i] = end;
                limitBoxes[i] = limit;
                table.Controls.Add(start, 2, i + 1);
                table.Controls.Add(end, 3, i + 1);
                table.Controls.Add(limit, 4, i + 1);
            }

            return table;
        }

        private static Label HeaderLabel(string text)
        {
            return new Label
            {
                AutoSize = false,
                BackColor = Ui.Panel,
                ForeColor = Color.White,
                Font = new Font("Segoe UI", 9.0f, FontStyle.Bold),
                Text = text,
                TextAlign = ContentAlignment.MiddleLeft,
                Dock = DockStyle.Fill,
                Padding = new Padding(4, 0, 4, 0)
            };
        }

        private static Label RowLabel(string text)
        {
            return new Label
            {
                AutoSize = false,
                BackColor = Ui.Panel,
                ForeColor = Color.White,
                Text = text,
                TextAlign = ContentAlignment.MiddleLeft,
                Dock = DockStyle.Fill,
                Padding = new Padding(4, 0, 4, 0)
            };
        }

        private static NumericUpDown NumberBox(int maximum)
        {
            return new NumericUpDown
            {
                Minimum = 0,
                Maximum = maximum,
                Increment = 1,
                BackColor = Ui.Panel,
                ForeColor = Color.White,
                TextAlign = HorizontalAlignment.Right,
                Dock = DockStyle.Fill,
                Margin = new Padding(5, 7, 5, 7)
            };
        }

        private static MaskedTextBox TimeBox()
        {
            return new MaskedTextBox
            {
                Mask = "00:00",
                PromptChar = '_',
                BackColor = Ui.Panel,
                ForeColor = Color.White,
                TextAlign = HorizontalAlignment.Center,
                Dock = DockStyle.Fill,
                Margin = new Padding(5, 7, 5, 7)
            };
        }

        private void LoadDraft()
        {
            loading = true;
            nameBox.Text = draft.Name ?? string.Empty;
            profileEnabledBox.Checked = draft.Enabled;

            for (int i = 0; i < DayCount; i++)
            {
                ChildDayDraft day = draft.Days != null && i < draft.Days.Count ? draft.Days[i] : null;
                if (day == null)
                {
                    day = new ChildDayDraft();
                }

                dayEnabledBoxes[i].Checked = day.Enabled;
                startBoxes[i].Text = FormatTime(day.StartMinute);
                endBoxes[i].Text = FormatTime(day.EndMinute);
                SetValue(limitBoxes[i], day.DailyLimitSeconds / 60);
            }

            loading = false;
            for (int i = 0; i < DayCount; i++)
            {
                UpdateDayControls(i);
            }
        }

        private static void SetValue(NumericUpDown box, int value)
        {
            decimal decimalValue = value;
            if (decimalValue < box.Minimum)
            {
                decimalValue = box.Minimum;
            }
            if (decimalValue > box.Maximum)
            {
                decimalValue = box.Maximum;
            }

            box.Value = decimalValue;
        }

        private static string FormatTime(int minutes)
        {
            if (minutes < 0) minutes = 0;
            if (minutes > MinutesPerDay) minutes = MinutesPerDay;
            int hour = minutes / 60;
            int minute = minutes % 60;
            return TwoDigits(hour) + ":" + TwoDigits(minute);
        }

        private static string TwoDigits(int value)
        {
            return (value < 10 ? "0" : string.Empty) + value.ToString();
        }

        private static bool TryParseTime(MaskedTextBox box, bool allowEndOfDay, out int minutes)
        {
            minutes = 0;
            if (box == null || !box.MaskCompleted)
            {
                return false;
            }

            string text = box.Text ?? string.Empty;
            if (text.Length != 5 || text[2] != ':' ||
                !IsAsciiDigit(text[0]) || !IsAsciiDigit(text[1]) ||
                !IsAsciiDigit(text[3]) || !IsAsciiDigit(text[4]))
            {
                return false;
            }

            int hour = (text[0] - '0') * 10 + (text[1] - '0');
            int minute = (text[3] - '0') * 10 + (text[4] - '0');
            if (minute > 59)
            {
                return false;
            }

            if (hour == 24)
            {
                if (!allowEndOfDay || minute != 0)
                {
                    return false;
                }

                minutes = MinutesPerDay;
                return true;
            }

            if (hour > 23)
            {
                return false;
            }

            minutes = hour * 60 + minute;
            return true;
        }

        private static bool IsAsciiDigit(char value)
        {
            return value >= '0' && value <= '9';
        }

        private void UpdateDayControls(int index)
        {
            if (loading || index < 0 || index >= DayCount || dayEnabledBoxes[index] == null)
            {
                return;
            }

            bool enabled = dayEnabledBoxes[index].Checked;
            startBoxes[index].Enabled = enabled;
            endBoxes[index].Enabled = enabled;
            limitBoxes[index].Enabled = enabled;
        }

        private ChildProfileDraft ReadDraft()
        {
            ChildProfileDraft candidate = draft.Clone();
            candidate.Name = (nameBox.Text ?? string.Empty).Trim();
            AvatarOption selectedAvatar = avatarBox.SelectedItem as AvatarOption;
            candidate.AvatarId = selectedAvatar == null ? "default" : selectedAvatar.Value;
            candidate.Enabled = profileEnabledBox.Checked;
            candidate.Days.Clear();

            for (int i = 0; i < DayCount; i++)
            {
                int startMinute;
                if (!TryParseTime(startBoxes[i], false, out startMinute))
                {
                    throw new InvalidOperationException("Podaj godzinę rozpoczęcia dla dnia: " + DayNames[i] + ".");
                }

                int endMinute;
                if (!TryParseTime(endBoxes[i], true, out endMinute))
                {
                    throw new InvalidOperationException("Podaj godzinę zakończenia dla dnia: " + DayNames[i] + ".");
                }

                candidate.Days.Add(new ChildDayDraft
                {
                    Enabled = dayEnabledBoxes[i].Checked,
                    StartMinute = startMinute,
                    EndMinute = endMinute,
                    DailyLimitSeconds = checked(decimal.ToInt32(limitBoxes[i].Value) * 60)
                });
            }

            return candidate;
        }

        private void SaveClicked(object sender, EventArgs e)
        {
            ChildProfileDraft candidate;
            try
            {
                candidate = ReadDraft();
            }
            catch (Exception exception)
            {
                SetError("Nie można odczytać formularza: " + exception.Message);
                return;
            }

            string validationError = ValidateDraft(candidate);
            if (validationError.Length > 0)
            {
                SetError(validationError);
                return;
            }

            try
            {
                // Pass a copy so a persistence owner cannot mutate the visible draft.
                saveDraft(candidate.Clone());
            }
            catch (Exception exception)
            {
                SetError("Nie zapisano profilu: " + ExceptionMessage(exception));
                return;
            }

            draft = candidate;
            errorLabel.Text = string.Empty;
            DialogResult = DialogResult.OK;
            Close();
        }

        private static string ExceptionMessage(Exception exception)
        {
            string message = exception == null ? string.Empty : exception.Message;
            return string.IsNullOrEmpty(message) ? "wystąpił konflikt lub błąd zapisu" : message;
        }

        private void SetError(string message)
        {
            errorLabel.Text = message ?? string.Empty;
            errorLabel.ForeColor = Ui.Danger;
        }
    }
}
